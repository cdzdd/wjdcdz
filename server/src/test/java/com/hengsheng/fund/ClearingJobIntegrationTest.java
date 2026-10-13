package com.hengsheng.fund;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClearingJobIntegrationTest {
    @Autowired Environment env;
    @Autowired Db db;
    @Autowired ClearingService clearing;
    @Autowired AccountService accounts;
    @Autowired TradeService trades;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    String csrf="";
    HttpResponse<String> call(String method,String path,Object body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path)).header("Content-Type","application/json");
        if(!csrf.isEmpty())b.header("X-CSRF-TOKEN",csrf);
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> map(HttpResponse<String> response){return json.readValue(response.body(),Map.class);}
    void login() throws Exception {
        csrf=map(call("GET","/auth/csrf",null)).get("token").toString();
        var login=call("POST","/auth/login",Map.of("username","admin","password","Admin12345!"));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        csrf=map(call("GET","/auth/csrf",null)).get("token").toString();
    }
    Map<String,Object> waitFor(long id) throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;
        while(System.nanoTime()<deadline){
            var response=call("GET","/admin/clearing-runs/"+id,null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var row=map(response);
            if(!java.util.Set.of("QUEUED","RUNNING","RECOVERING").contains(row.get("state")))return row;
            Thread.sleep(30);
        }
        throw new AssertionError("清算任务未完成："+id);
    }
    @Test void legacyPostIsAcceptedAndRequestKeyIsPermanent() throws Exception {
        login();
        var request=Map.of("type","PAY","date","2026-01-02","requestKey",UUID.randomUUID().toString());
        var accepted=call("POST","/admin/jobs",request);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long id=Db.id(map(accepted).get("runId"));
        var done=waitFor(id);
        assertThat(done).containsKeys("nodes","progress","counts","times","reason");
        assertThat(done.get("state")).isEqualTo("NO_WORK");
        assertThat(Db.id(map(call("POST","/admin/jobs",request)).get("runId"))).isEqualTo(id);
    }
    @Test void completedScopeCanBeScannedAgainWithNewRequestKey() throws Exception {
        login();
        var request=Map.of("type","PAY","date","2026-01-02","requestKey",UUID.randomUUID().toString());
        var first=call("POST","/admin/clearing-runs",request);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        long id=Db.id(map(first).get("runId"));waitFor(id);
        String name="due"+UUID.randomUUID().toString().substring(0,10);
        long uid=db.insert("INSERT INTO users(username,password_hash,display_name,role,opened) VALUES(?,'unused','新增到期业务','ADMIN',TRUE)",name);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(name,null,java.util.List.of()));
        long payout;
        try{
            var card=accounts.bind(Map.of("bankName","模拟银行","holderName","新增到期业务","requestKey","bind"));
            payout=trades.payout(uid,Db.id(card.get("tradingAccountId")),Db.id(card.get("id")),"TEST",UUID.randomUUID().toString(),new java.math.BigDecimal("7.11"),java.time.LocalDate.of(2026,1,2));
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        long next=Db.id(map(call("POST","/admin/clearing-runs",Map.of("type","PAY","date","2026-01-02","requestKey",UUID.randomUUID().toString()))).get("runId"));
        assertThat(next).isNotEqualTo(id);assertThat(waitFor(next).get("state")).isEqualTo("COMPLETED");
        assertThat(db.one("SELECT status FROM payouts WHERE id=?",payout).get("status")).isEqualTo("PAID");
        assertThat(Db.id(map(call("POST","/admin/clearing-runs",request)).get("runId"))).isEqualTo(id);
    }
    @Test void manualAndSchedulerCoalesceAndQueueFullIsExplicit() throws Exception {
        login();call("POST","/admin/clock",Map.of("dateTime","2026-09-01T16:00:00"));
        long admin=db.scalar("SELECT id FROM users WHERE username='admin'").longValue();
        long revoked=db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,'unused','执行时撤权','OPERATOR')","revoked"+UUID.randomUUID().toString().substring(0,10));
        var ids=new java.util.ArrayList<Long>();
        // A dedicated product has unpublished prices on every run, so repeated suites cannot outrun the queue barrier.
        long lockedFund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,state) VALUES(?,'队列阻塞夹具','BOND','测试',1,1,'OPEN')","QUEUE"+UUID.randomUUID().toString().substring(0,12));
        try(var lock=db.jdbc.getDataSource().getConnection()){
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("SELECT id FROM fund_classes WHERE id=? FOR UPDATE")){statement.setLong(1,lockedFund);statement.executeQuery().close();}
            long active=clearing.submit("MARKET",java.time.LocalDate.of(2026,9,1),Map.of(),admin,UUID.randomUUID().toString());ids.add(active);
            long deadline=System.nanoTime()+5_000_000_000L;
            while(!"RUNNING".equals(clearing.get(active).get("state"))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(clearing.get(active).get("state")).isEqualTo("RUNNING");
            try(var submitters=java.util.concurrent.Executors.newFixedThreadPool(2)){
                var manual=submitters.submit(()->clearing.submit("MARKET",java.time.LocalDate.of(2026,9,1),Map.of(),admin,UUID.randomUUID().toString()));
                var scheduled=submitters.submit(()->clearing.submit("MARKET",java.time.LocalDate.of(2026,9,1),Map.of(),null,UUID.randomUUID().toString()));
                assertThat(manual.get()).isEqualTo(active);assertThat(scheduled.get()).isEqualTo(active);
            }
            for(int i=0;i<4;i++)ids.add(clearing.submit("PAY",java.time.LocalDate.of(2025,12,20+i),Map.of(),i==2?revoked:admin,UUID.randomUUID().toString()));
            long rejected=clearing.submit("PAY",java.time.LocalDate.of(2025,12,25),Map.of(),admin,UUID.randomUUID().toString());
            assertThat(clearing.get(rejected)).containsEntry("state","REJECTED_QUEUE_FULL");
            assertThat(clearing.get(rejected).get("reason").toString()).contains("队列已满");
            var httpRejected=call("POST","/admin/clearing-runs",Map.of("type","PAY","date","2025-12-26","requestKey",UUID.randomUUID().toString()));
            assertThat(httpRejected.statusCode()).as(httpRejected.body()).isEqualTo(429);
            assertThat(map(httpRejected)).containsKeys("runId","state","message");
            db.update("UPDATE users SET enabled=FALSE WHERE id=?",revoked);
            call("POST","/admin/clock",Map.of("dateTime","2026-09-02T16:00:00"));
            lock.rollback();
        }finally{for(long id:ids)waitFor(id);db.update("DELETE FROM nav_records WHERE fund_class_id=?",lockedFund);db.update("DELETE FROM fund_classes WHERE id=?",lockedFund);}
        assertThat(clearing.get(ids.get(3)).get("state")).isEqualTo("FAILED");
        assertThat(clearing.get(ids.get(3)).get("reason").toString()).contains("停用");
        assertThat(clearing.get(ids.get(1)).get("businessTime").toString()).startsWith("2026-09-01T16:00");
        var history=(Map<?,?>)clearing.get(ids.get(1)).get("result");
        assertThat(history.get("businessTime").toString()).startsWith("2026-09-01T16:00");
    }
    @Test void startupRecoveryResumesPersistedRunningTask() throws Exception {
        login();
        long id=db.insert("INSERT INTO clearing_runs(type,business_date,parameters,fingerprint,active_scope,business_time,state,reason,created_at) VALUES('PAY','2026-01-02','{}',?,?,'2026-01-02 16:00:00','RUNNING','',NOW(6))",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        int order=0;for(String name:java.util.List.of("ADVANCED","PAY","SYNC_PAYMENTS","RECONCILE"))db.insert("INSERT INTO clearing_run_nodes(run_id,node_order,name,state,reason) VALUES(?,?,?,'RUNNING','')",id,++order,name);
        clearing.recover();
        assertThat(waitFor(id).get("state")).isEqualTo("NO_WORK");
        assertThat(db.scalar("SELECT COUNT(*) FROM clearing_run_nodes WHERE run_id=? AND state IN ('PENDING','RUNNING')",id).intValue()).isZero();
    }
}
