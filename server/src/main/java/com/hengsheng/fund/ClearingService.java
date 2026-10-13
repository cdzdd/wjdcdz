package com.hengsheng.fund;

import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ClearingService {
    private static final Logger log=LoggerFactory.getLogger(ClearingService.class);
    private static final Set<String> BATCH_TYPES=Set.of("ALL","MARKET","CONFIRM","SIP","MONEY","PAY");
    private static final Set<String> SPECIAL_TYPES=Set.of("OFFERING_FINALIZE","DIVIDEND_SNAPSHOT","DIVIDEND_EXECUTE","LIQUIDATION","RECLEAR","EXPORT");
    private final Db db;
    private final AccountService accounts;
    private final ObjectProvider<CoreController> core;
    private final ObjectProvider<OfferingService> offerings;
    private final ObjectProvider<DividendService> dividends;
    private final ObjectProvider<LifecycleService> lifecycle;
    private final ObjectProvider<ReclearingService> reclearing;
    private final ObjectProvider<ApplicationExportService> exports;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"clearing-worker");t.setDaemon(true);return t;});
    private volatile boolean started;
    public ClearingService(Db db,AccountService accounts,ObjectProvider<CoreController> core,ObjectProvider<OfferingService> offerings,
            ObjectProvider<DividendService> dividends,ObjectProvider<LifecycleService> lifecycle,ObjectProvider<ReclearingService> reclearing,ObjectProvider<ApplicationExportService> exports,PlatformTransactionManager manager){
        this.db=db;this.accounts=accounts;this.core=core;this.offerings=offerings;this.dividends=dividends;this.lifecycle=lifecycle;this.reclearing=reclearing;this.exports=exports;this.tx=new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start(){if(started)return;recover();started=true;worker.scheduleWithFixedDelay(this::poll,0,100,TimeUnit.MILLISECONDS);}
    /** Only called during single-instance startup, before the worker starts. */
    synchronized void recover(){
        ClearingExecutionContext.enter(0,null,null);
        try{tx.executeWithoutResult(status->{
            db.update("UPDATE clearing_run_nodes n JOIN clearing_runs r ON r.id=n.run_id SET n.state='PENDING',n.reason='进程重启后恢复',n.finished_at=NULL WHERE r.state='RUNNING' AND n.state='RUNNING'");
            db.update("UPDATE clearing_runs SET state='QUEUED',reason='进程重启后恢复' WHERE state='RUNNING'");
        });}finally{ClearingExecutionContext.clear();}
    }
    private void poll(){
        try{
            List<Map<String,Object>> rows;
            ClearingExecutionContext.enter(0,null,null);
            try{rows=db.rows("SELECT id FROM clearing_runs WHERE state='QUEUED' ORDER BY id LIMIT 1");}finally{ClearingExecutionContext.clear();}
            if(!rows.isEmpty())execute(Db.id(rows.getFirst().get("id")));
        }
        catch(Exception ex){log.error("Clearing queue polling failed",ex);}
    }
    public synchronized long submit(String type,LocalDate businessDate,Map<String,Object> parameters,Long actorId,String requestKey){
        if(!BATCH_TYPES.contains(type)&&!SPECIAL_TYPES.contains(type))throw ApiException.bad("任务类型无效");
        if(requestKey==null||requestKey.isBlank()||requestKey.length()>200)throw ApiException.bad("requestKey须为1至200字符");
        validateActor(type,actorId);
        var input=new TreeMap<String,Object>(parameters);input.remove("requestKey");input.remove("type");input.remove("date");input.remove("businessDate");
        // Liquidation's date is also a business input.
        if(type.equals("LIQUIDATION"))input.put("date",businessDate.toString());
        String fingerprint=TradeService.fingerprint(Map.of("type",type,"date",businessDate.toString(),"parameters",input));
        long actorKey=actorId==null?0:actorId;
        return tx.execute(status->{
            var prior=db.rows("SELECT run_id,fingerprint FROM clearing_requests WHERE actor_key=? AND request_key=?",actorKey,requestKey);
            if(!prior.isEmpty()){
                if(!fingerprint.equals(prior.getFirst().get("fingerprint")))throw ApiException.conflict("请求键已经用于不同清算参数");
                return Db.id(prior.getFirst().get("runId"));
            }
            var businessTime=accounts.clock();if(businessDate.isAfter(businessTime.toLocalDate()))throw ApiException.conflict("不能处理未来日期的任务");
            String scope=TradeService.fingerprint(Map.of("type",type,"date",businessDate.toString(),"target",input.getOrDefault("targetId",input.getOrDefault("fundClassId",0))));
            var active=db.rows("SELECT id,fingerprint FROM clearing_runs WHERE active_scope=?",scope);
            long id;
            if(!active.isEmpty()){
                if(!fingerprint.equals(active.getFirst().get("fingerprint")))throw ApiException.conflict("同一业务范围已有不同参数的活动清算");
                id=Db.id(active.getFirst().get("id"));
            }else{
                boolean full=db.scalar("SELECT COUNT(*) FROM clearing_runs WHERE state='QUEUED'").intValue()>=4;
                id=db.insert("INSERT INTO clearing_runs(type,business_date,parameters,fingerprint,active_scope,actor_id,business_time,state,reason,created_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    type,businessDate,json.writeValueAsString(input),fingerprint,full?null:scope,actorId,businessTime,full?"REJECTED_QUEUE_FULL":"QUEUED",full?"清算等待队列已满，请使用新的请求键稍后提交":"等待后台处理",actualTime(),full?actualTime():null);
                int order=0;for(String node:nodes(type))db.insert("INSERT INTO clearing_run_nodes(run_id,node_order,name,state,reason) VALUES(?,?,?,?,?)",id,++order,node,full?"SKIPPED":"PENDING",full?"队列已满":"");
            }
            db.update("INSERT INTO clearing_requests(actor_key,request_key,run_id,fingerprint) VALUES(?,?,?,?)",actorKey,requestKey,id,fingerprint);return id;
        });
    }
    private void validateActor(String type,Long actorId){
        if(actorId==null)return;
        var user=db.one("SELECT role,enabled FROM users WHERE id=?",actorId);
        if(!Db.yes(user.get("enabled")))throw new ApiException(403,"USER_DISABLED","提交清算的账户已停用");
        String role=user.get("role").toString();
        if(!(role.equals("ADMIN")||(!Set.of("LIQUIDATION","RECLEAR").contains(type)&&role.equals("OPERATOR"))))throw new ApiException(403,"FORBIDDEN","没有执行该清算任务的权限");
    }
    public synchronized long bindRequestAlias(long runId,String requestKey,Long actorId){
        if(requestKey==null||requestKey.isBlank()||requestKey.length()>200)throw ApiException.bad("requestKey须为1至200字符");
        var run=db.one("SELECT type,fingerprint FROM clearing_runs WHERE id=?",runId);validateActor(run.get("type").toString(),actorId);long actorKey=actorId==null?0:actorId;
        return tx.execute(status->{var prior=db.rows("SELECT run_id,fingerprint FROM clearing_requests WHERE actor_key=? AND request_key=?",actorKey,requestKey);
            if(!prior.isEmpty()){if(Db.id(prior.getFirst().get("runId"))!=runId||!Objects.equals(prior.getFirst().get("fingerprint"),run.get("fingerprint")))throw ApiException.conflict("请求键已经用于不同清算参数");return runId;}
            db.update("INSERT INTO clearing_requests(actor_key,request_key,run_id,fingerprint) VALUES(?,?,?,?)",actorKey,requestKey,runId,run.get("fingerprint"));return runId;
        });
    }
    private List<String> nodes(String type){
        if(type.equals("EXPORT"))return List.of("SEAL_APPLICATIONS","EXPORT_APPLICATIONS");
        if(type.equals("ALL"))return List.of("MARKET","CONFIRM","ADVANCED","PAY","SYNC_PAYMENTS","RECONCILE");
        if(type.equals("PAY"))return List.of("ADVANCED","PAY","SYNC_PAYMENTS","RECONCILE");
        if(BATCH_TYPES.contains(type))return List.of(type,"ADVANCED","RECONCILE");
        return List.of(type);
    }
    public Map<String,Object> get(long runId){
        var row=db.one("SELECT * FROM clearing_runs WHERE id=?",runId);
        var nodes=db.rows("SELECT * FROM clearing_run_nodes WHERE run_id=? ORDER BY node_order",runId);row.put("nodes",nodes);row.put("runId",runId);
        long completed=nodes.stream().filter(n->!Set.of("PENDING","RUNNING").contains(n.get("state"))).count();
        row.put("progress",Map.of("completed",completed,"total",nodes.size(),"percent",nodes.isEmpty()?100:completed*100/nodes.size()));
        if(Set.of("QUEUED","RUNNING").contains(row.get("state")))for(String name:List.of("processed","waiting","failed"))row.put(name,nodes.stream().mapToInt(n->count(n,name)).sum());
        row.put("counts",Map.of("processed",row.get("processed"),"waiting",row.get("waiting"),"failed",row.get("failed")));
        var times=new LinkedHashMap<String,Object>();for(String key:List.of("businessTime","createdAt","startedAt","finishedAt"))times.put(key,row.get(key));row.put("times",times);
        if(row.get("result")!=null)row.put("result",json.readValue(row.get("result").toString(),Map.class));
        return row;
    }
    public List<Map<String,Object>> list(){return db.rows("SELECT id run_id,type,business_date,state,processed,waiting,failed,reason,created_at,started_at,finished_at FROM clearing_runs ORDER BY id DESC LIMIT 200");}
    public ResponseEntity<Map<String,Object>> submissionResponse(long runId,Map<String,Object> extra){
        var run=get(runId);var body=new LinkedHashMap<>(extra);body.put("runId",runId);body.put("state",run.get("state"));
        if("REJECTED_QUEUE_FULL".equals(run.get("state"))){body.put("code","QUEUE_FULL");body.put("message",run.get("reason"));return ResponseEntity.status(429).body(body);}
        return ResponseEntity.accepted().body(body);
    }
    /** Background entry point; deliberately has no HTTP mapping or outer business transaction. */
    public void execute(long runId){
        Map<String,Object> run;
        ClearingExecutionContext.enter(runId,null,null);
        try{run=db.one("SELECT * FROM clearing_runs WHERE id=?",runId);}finally{ClearingExecutionContext.clear();}
        Long actor=run.get("actorId")==null?null:Db.id(run.get("actorId"));
        ClearingExecutionContext.enter(runId,LocalDateTime.parse(run.get("businessTime").toString()),actor);
        try{
            if(db.update("UPDATE clearing_runs SET state='RUNNING',started_at=COALESCE(started_at,?),reason='' WHERE id=? AND state='QUEUED'",actualTime(),runId)!=1)return;
            String type=run.get("type").toString();validateActor(type,actor);
            LocalDate date=LocalDate.parse(run.get("businessDate").toString());
            Map<String,Object> parameters=json.readValue(run.get("parameters").toString(),Map.class);
            Map<String,Object> result;
            if(BATCH_TYPES.contains(type))result=core.getObject().runJobs(type,date);
            else if(type.equals("EXPORT"))result=exports.getObject().generate(Db.id(parameters.get("exportId")));
            else result=node(type,()->switch(type){
                case "RECLEAR"->reclearing.getObject().apply(runId);
                case "OFFERING_FINALIZE"->offerings.getObject().finalizeOffering(Db.id(parameters.get("targetId")),parameters);
                case "DIVIDEND_SNAPSHOT"->dividends.getObject().snapshot(Db.id(parameters.get("targetId")));
                case "DIVIDEND_EXECUTE"->dividends.getObject().execute(Db.id(parameters.get("targetId")));
                case "LIQUIDATION"->{
                    var outcome=new LinkedHashMap<>(lifecycle.getObject().liquidate(parameters));
                    outcome.put("processed",outcome.get("items") instanceof List<?> items?Math.max(1,items.size()):1);
                    outcome.put("waiting",0);outcome.put("failed",0);yield outcome;
                }
                default->throw ApiException.bad("任务类型无效");
            });
            String state=result.containsKey("status")&&Set.of("COMPLETED","NO_WORK","WAITING","FAILED","PARTIAL").contains(result.get("status"))?result.get("status").toString():state(result);
            finish(runId,state,result,details(result));
        }catch(Exception ex){
            String reason=Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName());
            finish(runId,"FAILED",Map.of("processed",0,"waiting",0,"failed",1,"details",List.of(reason)),reason);
        }finally{ClearingExecutionContext.clear();}
    }
    private void finish(long id,String state,Map<String,Object> result,String reason){
        tx.executeWithoutResult(status->{
            db.update("UPDATE clearing_run_nodes SET state='SKIPPED',reason='前序节点未完成',finished_at=? WHERE run_id=? AND state='PENDING'",actualTime(),id);
            db.update("UPDATE clearing_runs SET state=?,active_scope=NULL,processed=?,waiting=?,failed=?,reason=?,result=?,finished_at=? WHERE id=?",state,count(result,"processed"),count(result,"waiting"),count(result,"failed"),reason,json.writeValueAsString(result),actualTime(),id);
        });
    }
    Map<String,Object> node(String name,Supplier<Map<String,Object>> action){
        long runId=ClearingExecutionContext.currentRunId();if(runId==0)return action.get();
        var prior=db.one("SELECT state,result FROM clearing_run_nodes WHERE run_id=? AND name=?",runId,name);
        if("COMPLETED".equals(prior.get("state"))||"NO_WORK".equals(prior.get("state")))return json.readValue(prior.get("result").toString(),Map.class);
        db.update("UPDATE clearing_run_nodes SET state='RUNNING',started_at=?,finished_at=NULL,reason='' WHERE run_id=? AND name=?",actualTime(),runId,name);
        try{
            var result=action.get();db.update("UPDATE clearing_run_nodes SET state=?,processed=?,waiting=?,failed=?,reason=?,result=?,finished_at=? WHERE run_id=? AND name=?",
                state(result),count(result,"processed"),count(result,"waiting"),count(result,"failed"),details(result),json.writeValueAsString(result),actualTime(),runId,name);return result;
        }catch(RuntimeException ex){db.update("UPDATE clearing_run_nodes SET state='FAILED',failed=1,reason=?,finished_at=? WHERE run_id=? AND name=?",Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName()),actualTime(),runId,name);throw ex;}
    }
    void refreshNodeResult(String name,Map<String,Object> result){
        long id=ClearingExecutionContext.currentRunId();if(id==0)return;
        db.update("UPDATE clearing_run_nodes SET state=?,processed=?,waiting=?,failed=?,reason=?,result=? WHERE run_id=? AND name=?",state(result),count(result,"processed"),count(result,"waiting"),count(result,"failed"),details(result),json.writeValueAsString(result),id,name);
    }
    private static int count(Map<String,Object> result,String key){return Integer.parseInt(result.getOrDefault(key,0).toString());}
    private static String state(Map<String,Object> result){if(Set.of("COMPLETED","NO_WORK","WAITING","FAILED","PARTIAL").contains(result.getOrDefault("status","")))return result.get("status").toString();return count(result,"failed")>0?(count(result,"processed")>0?"PARTIAL":"FAILED"):count(result,"waiting")>0?"WAITING":count(result,"processed")>0?"COMPLETED":result.containsKey("id")?"COMPLETED":"NO_WORK";}
    private static String details(Map<String,Object> result){return result.get("details") instanceof List<?> values?String.join("\n",values.stream().map(Object::toString).toList()):"";}
    private static LocalDateTime actualTime(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
    @PreDestroy public void stop(){worker.shutdownNow();}
}
