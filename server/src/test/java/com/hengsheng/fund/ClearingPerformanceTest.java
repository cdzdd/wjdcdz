package com.hengsheng.fund;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in HTTP benchmark. The real clearing worker and both real pools remain unchanged. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named="clearing.performance",matches="seed|smoke|quick|full")
class ClearingPerformanceTest {
    @TempDir static Path directory;
    static String expectedDatabase;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        String url=Objects.requireNonNull(System.getenv("DB_TEST_URL"),"Load the isolated database through Seed-PerformanceData.ps1");
        var database=java.util.regex.Pattern.compile("^jdbc:mysql://[^/]+/(fund_sim_test|fund_sim_upgrade_test)\\?.*").matcher(url);
        if(!database.matches())throw new IllegalStateException("Performance fixtures require an approved isolated test database");
        expectedDatabase=database.group(1);
        registry.add("spring.datasource.url",()->url);
        registry.add("fund.exports.directory",()->directory.toString());
    }
    @Autowired Db db;
    @Autowired Environment environment;
    @Autowired AccountService accounts;
    @Autowired PortfolioService portfolios;
    @Autowired ClearingService clearing;
    @Autowired ApplicationExportService exports;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("realtimeDataSource") HikariDataSource realtimePool;
    @Autowired @Qualifier("batchDataSource") HikariDataSource batchPool;
    record Account(long user,long trade,long card,long fundAccount,String username) {}
    record Session(HttpClient client,String csrf,Account account) {}
    record Sample(int sequence,int session,String operation,int status,double latencyMillis,double scheduledLatencyMillis,boolean duringBatch,String error) {}
    final List<Map<String,Object>> reports=new ArrayList<>();
    final List<Account> seededAccounts=new ArrayList<>();
    String tag,password;
    LocalDate historyDate,pendingDate,confirmationDate;
    long registrar,actor;
    boolean datesOwned;
    final List<Long> funds=new ArrayList<>(),jobIds=new ArrayList<>();

    @Test void measureRealtimeAgainstRealClearingAndExport() throws Exception {
        String profile=System.getProperty("clearing.performance");
        assertThat(db.one("SELECT DATABASE() name").get("name")).isEqualTo(expectedDatabase);
        assertThat(db.scalar("SELECT COUNT(*) FROM clearing_runs WHERE state IN ('QUEUED','RUNNING')")).as("The shared test database must have no unfinished batch work").isEqualByComparingTo(BigDecimal.ZERO);
        var existing=portfolios.reconciliation();assertThat(existing.get("ok")).as("Existing test assets must reconcile before measuring: "+existing.get("issues")).isEqualTo(true);
        password="PerfTest12345!";
        int seconds=profile.equals("full")?600:profile.equals("quick")?60:5;
        int warmup=profile.equals("full")?120:profile.equals("quick")?10:2;
        int repeats=profile.equals("full")?3:1;
        for(int repetition=1;repetition<=repeats;repetition++)for(String group:profile.equals("seed")?List.of("SEED"):List.of("BASELINE","CLEARING","EXPORT")) {
            try {
                seed();
                if(group.equals("SEED")){verifyAssets();reports.add(Map.of("group","SEED","customers",1000,"accounts",2000,"funds",20,"historyApplications",10000,"pendingConfirmations",2000,"assetLedgerMatches",true));}
                else reports.add(measure(group,repetition,seconds,warmup));
            } finally { cleanup(); }
            if(group.equals("EXPORT")) {
                final int trial=repetition;
                double baseline=((Number)reports.stream().filter(r->r.get("group").equals("BASELINE")&&r.get("repetition").equals(trial)).findFirst().orElseThrow().get("p95Millis")).doubleValue();
                double threshold=Math.max(baseline*1.2,baseline+50);
                for(var report:reports)if(report.get("repetition").equals(trial)) {
                    report.put("parallelLatencyThresholdMillis",threshold);
                    report.put("parallelLatencyGatePassed",((Number)report.get("p95Millis")).doubleValue()<=threshold);
                    if(!report.get("group").equals("BASELINE"))report.put("parallelWindowLatencyGatePassed",((Number)report.get("batchOverlapP95Millis")).doubleValue()<=threshold);
                }
                System.out.println("CLEARING_PERF_COMPARISON repetition="+trial+" baselineP95Millis="+baseline+" parallelThresholdMillis="+threshold);
            }
            Path output=Path.of(System.getProperty("clearing.performance.output","target/clearing-performance.json"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,json.writeValueAsString(Map.of("profile",profile,"database",expectedDatabase,"completeAcceptanceProfile",profile.equals("full")&&reports.size()==9,"results",reports)));
        }
        System.out.println("CLEARING_PERF_FINISHED profile="+profile+" groups="+reports.size()+" syntheticRowsRemoved=true");
    }

    private void seed() {
        tag="PERF"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        seededAccounts.clear();funds.clear();jobIds.clear();registrar=0;actor=0;datesOwned=false;
        // A deliberately old, unused date prevents this full-platform scanner touching other tests' orders.
        do {historyDate=LocalDate.of(1900,1,1).plusDays(ThreadLocalRandom.current().nextInt(14000));}
        while(db.scalar("SELECT COUNT(*) FROM trading_calendar WHERE date BETWEEN ? AND ?",historyDate,historyDate.plusDays(2)).signum()!=0
            ||db.scalar("SELECT COUNT(*) FROM business_number_sequences WHERE business_date BETWEEN ? AND ?",historyDate,historyDate.plusDays(2)).signum()!=0
            ||db.scalar("SELECT COUNT(*) FROM application_days WHERE business_date BETWEEN ? AND ?",historyDate,historyDate.plusDays(2)).signum()!=0);
        pendingDate=historyDate.plusDays(1);confirmationDate=historyDate.plusDays(2);
        assertThat(db.scalar("SELECT COUNT(*) FROM orders WHERE status='SUBMITTED' AND confirmation_date<=?",confirmationDate)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(db.scalar("SELECT COUNT(*) FROM conversions WHERE status='SUBMITTED' AND confirmation_date<=?",confirmationDate)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(accounts.clock().toLocalDate()).isAfter(confirmationDate);
        datesOwned=true;for(int i=0;i<3;i++)db.update("INSERT INTO trading_calendar(date,open) VALUES(?,TRUE)",historyDate.plusDays(i));
        registrar=db.insert("INSERT INTO registrars(name) VALUES(?)",tag);
        actor=db.insert("INSERT INTO users(username,password_hash,display_name,role,opened) VALUES(?,?,?,'ADMIN',TRUE)",tag+"_actor",passwords.encode(password),tag);
        for(int i=0;i<20;i++) {
            long fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,purchase_rate,redemption_rate) VALUES(?,?,'BOND','性能夹具',?,1,0,0)",tag+i,tag+i,registrar);funds.add(fund);
            for(LocalDate date:List.of(historyDate,pendingDate))db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,?,1)",fund,date);
        }
        String hash=passwords.encode(password);
        var users=new ArrayList<Object[]>();for(int i=0;i<1000;i++)users.add(new Object[]{tag+"_"+i,hash,tag,accounts.clock().toLocalDate().plusYears(1)});
        db.jdbc.batchUpdate("INSERT INTO users(username,password_hash,display_name,opened,initial_granted,risk_level,risk_expires_at) VALUES(?,?,?,TRUE,TRUE,5,?)",users);
        var customerRows=db.rows("SELECT id,username FROM users WHERE display_name=? AND role='INVESTOR' ORDER BY id",tag);
        var cards=new ArrayList<Object[]>();for(var user:customerRows)for(int card=0;card<2;card++)cards.add(new Object[]{user.get("id"),tag+"-"+user.get("id")+"-"+card,tag,"card-"+card});
        db.jdbc.batchUpdate("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'性能模拟银行',?,?,?)",cards);
        db.update("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) SELECT c.user_id,CONCAT(?,c.id),c.id FROM bank_cards c JOIN users u ON u.id=c.user_id WHERE u.display_name=?",tag,tag);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) SELECT t.id,t.bank_card_id,1 FROM trading_accounts t JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("INSERT INTO fund_accounts(user_id,registrar_id,account_no) SELECT id,?,CONCAT(?,id) FROM users WHERE display_name=? AND role='INVESTOR'",registrar,tag,tag);
        db.update("INSERT INTO trading_fund_accounts(trading_account_id,fund_account_id) SELECT t.id,f.id FROM trading_accounts t JOIN fund_accounts f ON f.user_id=t.user_id WHERE f.registrar_id=?",registrar);
        db.update("INSERT INTO wallets(trading_account_id,available) SELECT t.id,100000 FROM trading_accounts t JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) SELECT t.user_id,t.id,'INITIAL',?,'CREDIT',100000,0,100000,? FROM trading_accounts t JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag,historyDate.atTime(8,0),tag);
        for(var row:db.rows("SELECT t.user_id,t.id,t.bank_card_id,f.id fund_account_id,u.username FROM trading_accounts t JOIN users u ON u.id=t.user_id JOIN fund_accounts f ON f.user_id=u.id AND f.registrar_id=? WHERE u.display_name=? ORDER BY t.id",registrar,tag))
            seededAccounts.add(new Account(Db.id(row.get("userId")),Db.id(row.get("id")),Db.id(row.get("bankCardId")),Db.id(row.get("fundAccountId")),row.get("username").toString()));
        assertThat(seededAccounts).hasSize(2000);
        insertOrders(10000,true);insertOrders(2000,false);
        db.update("INSERT INTO business_number_registry(kind,business_no,business_date,sequence_no,source_type,source_id,created_at,business_time) SELECT 'APPLICATION',o.application_no,o.application_date,CAST(RIGHT(o.application_no,6) AS UNSIGNED),'ORDER',o.id,NOW(6),o.created_at FROM orders o JOIN users u ON u.id=o.user_id WHERE u.display_name=?",tag);
        for(var pair:List.of(Map.entry(historyDate,10000),Map.entry(pendingDate,2000)))db.update("INSERT INTO business_number_sequences(kind,business_date,sequence_value) VALUES('APPLICATION',?,?)",pair.getKey(),pair.getValue());
        db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) SELECT o.user_id,o.trading_account_id,'ORDER',CAST(o.id AS CHAR),'RESERVE',-o.amount,o.amount,0,o.created_at FROM orders o JOIN users u ON u.id=o.user_id WHERE u.display_name=?",tag);
        db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) SELECT o.user_id,o.trading_account_id,'ORDER',CAST(o.id AS CHAR),'CONSUME',0,-o.amount,-o.amount,o.created_at FROM orders o JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.status='COMPLETED'",tag);
        db.update("UPDATE wallets w JOIN trading_accounts t ON t.id=w.trading_account_id JOIN users u ON u.id=t.user_id SET w.available=99850,w.frozen=100 WHERE u.display_name=?",tag);
        db.update("INSERT INTO position_lots(user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,cost,acquired_date,source_type,source_id) SELECT o.user_id,o.trading_account_id,o.fund_class_id,o.source_card_id,o.fund_account_id,o.shares,o.amount,o.confirmation_date,'PURCHASE',CAST(o.id AS CHAR) FROM orders o JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.status='COMPLETED'",tag);
        db.update("INSERT INTO share_entries(user_id,trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,shares_delta,frozen_delta,cost_delta,created_at) SELECT l.user_id,l.trading_account_id,l.fund_class_id,l.id,'PURCHASE',l.source_id,'ADD',l.shares,0,l.cost,? FROM position_lots l JOIN users u ON u.id=l.user_id WHERE u.display_name=?",historyDate.atTime(16,0),tag);
        verifyAssets();
        System.out.println("CLEARING_PERF_SEED customers=1000 accounts=2000 funds=20 historyApplications=10000 pendingConfirmations=2000 ledgerMatches=true");
    }

    private void insertOrders(int count,boolean historical) {
        LocalDate date=historical?historyDate:pendingDate;
        for(int start=0;start<count;start+=200) {
            var batch=new ArrayList<Object[]>();
            for(int i=start;i<Math.min(count,start+200);i++) {
                Account a=seededAccounts.get(i%2000);String key=tag+(historical?"H":"P")+i;
                batch.add(new Object[]{key,a.user(),funds.get(i%20),a.trade(),a.card(),a.fundAccount(),historical?"COMPLETED":"SUBMITTED",historical?10:100,historical?10:0,date,date.plusDays(1),date.plusDays(2),key,date.atTime(10,0),date.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format(Locale.ROOT,"%06d",i+1),date.plusDays(1),historical?BigDecimal.ONE:null,historical?10:0});
            }
            db.jdbc.batchUpdate("""
                INSERT INTO orders(order_no,user_id,type,fund_class_id,trading_account_id,source_card_id,binding_version,fund_account_id,status,amount,shares,application_date,confirmation_date,payment_date,request_key,request_fingerprint,purchase_rate,redemption_rate,risk_level,rule_version,cutoff_time,created_at,application_no,planned_confirmation_date,nav,net_amount)
                VALUES(?,?,'PURCHASE',?,?,?,1,?,?,?,?,?,?,?,?, 'performance-seed',0,0,1,1,'15:00:00',?,?,?,?,?)
                """,batch);
        }
    }

    private Map<String,Object> measure(String group,int repetition,int seconds,int warmup) throws Exception {
        var sessions=new ArrayList<Session>();for(int i=0;i<20;i++)sessions.add(login(seededAccounts.get(i*2)));
        long warmupStart=System.nanoTime();load(sessions,warmup,"warmup",new AtomicBoolean());long warmupElapsed=(System.nanoTime()-warmupStart)/1_000_000;
        System.gc();long baselineHeap=heap();var peak=new AtomicLong(baselineHeap);var realtimePeak=new AtomicInteger();var batchPeak=new AtomicInteger();var active=new AtomicBoolean();
        var sampler=Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(()->{peak.accumulateAndGet(heap(),Math::max);realtimePeak.accumulateAndGet(realtimePool.getHikariPoolMXBean().getActiveConnections(),Math::max);batchPeak.accumulateAndGet(batchPool.getHikariPoolMXBean().getActiveConnections(),Math::max);},0,5,TimeUnit.MILLISECONDS);
        var batchExecutor=Executors.newSingleThreadExecutor();Future<Map<String,Object>> task=null;
        long begin=System.nanoTime();
        if(!group.equals("BASELINE")) {active.set(true);task=batchExecutor.submit(()->runBatch(group,active));}
        List<Sample> samples;
        Map<String,Object> batchResult=Map.of();
        try { samples=load(sessions,seconds,"measured",active);if(task!=null)batchResult=task.get(4,TimeUnit.MINUTES); }
        finally { sampler.shutdown();batchExecutor.shutdown();sampler.awaitTermination(5,TimeUnit.SECONDS);batchExecutor.awaitTermination(5,TimeUnit.SECONDS);sessions.forEach(s->s.client().close()); }
        long elapsed=(System.nanoTime()-begin)/1_000_000;
        verifyAssets();
        if(group.equals("CLEARING"))verifyConfirmations(batchResult);
        long errors=samples.stream().filter(s->s.status()!=200).count();
        var overlaps=samples.stream().filter(Sample::duringBatch).toList();
        var report=new LinkedHashMap<String,Object>();
        double loadElapsed=Math.max(seconds*1000.0,samples.stream().mapToDouble(s->s.sequence()*200.0+s.scheduledLatencyMillis()).max().orElse(0));
        report.put("group",group);report.put("repetition",repetition);report.put("warmupSeconds",warmup);report.put("measurementSeconds",seconds);report.put("sessions",20);report.put("targetRequestsPerSecond",5);report.put("scheduledRequests",seconds*5);report.put("completedRequests",samples.size());report.put("actualLoadElapsedMillis",loadElapsed);report.put("actualRequestsPerSecond",samples.size()*1000.0/loadElapsed);report.put("technicalErrors",errors);
        report.put("actualWarmupElapsedMillis",warmupElapsed);report.put("recordedAt",Instant.now().toString());report.put("historyApplicationDate",historyDate.toString());report.put("pendingApplicationDate",pendingDate.toString());report.put("clearingScanDate",confirmationDate.toString());
        report.put("p95Millis",p95(samples));report.put("maxMillis",samples.stream().mapToDouble(Sample::latencyMillis).max().orElse(0));report.put("p95ScheduledLatencyMillis",p95Scheduled(samples));report.put("batchOverlapRequests",overlaps.size());report.put("batchOverlapP95Millis",p95(overlaps));report.put("totalElapsedMillis",elapsed);report.put("batch",batchResult);
        report.put("sampledHeapBaselineBytes",baselineHeap);report.put("sampledHeapPeakBytes",peak.get());report.put("sampledHeapIncreaseBytes",peak.get()-baselineHeap);report.put("heapSamplingMillis",5);report.put("heapIncludesRealtimeWorkload",true);report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("realtimePoolSize",realtimePool.getMaximumPoolSize());report.put("batchPoolSize",batchPool.getMaximumPoolSize());report.put("observedRealtimeActivePeak",realtimePeak.get());report.put("observedBatchActivePeak",batchPeak.get());
        report.put("assetLedgerMatches",true);report.put("duplicateWithdrawalEffects",0);if(group.equals("CLEARING"))report.put("confirmationAppliedOnce",true);report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("physicalMemoryBytes",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize());
        report.put("jvmArguments",ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("customers",1000);report.put("accounts",2000);report.put("funds",20);report.put("historyApplications",10000);report.put("pendingConfirmationsAtStart",2000);
        report.put("latencyThresholdMillis",1000);report.put("absoluteLatencyGatePassed",p95(samples)<=1000);report.put("zeroTechnicalErrorsGatePassed",errors==0);
        System.out.println("CLEARING_PERF "+json.writeValueAsString(report));report.put("requestSamples",samples);
        assertThat(errors).as("Real HTTP workload technical errors").isZero();
        if(!group.equals("BASELINE"))assertThat(overlaps).as("Requests really overlapped queued/running batch work").isNotEmpty();
        return report;
    }

    private Map<String,Object> runBatch(String group,AtomicBoolean active) throws Exception {
        long start=System.nanoTime(),run,export=0;String key=tag+group;
        try {
            if(group.equals("CLEARING"))run=clearing.submit("CONFIRM",confirmationDate,Map.of(),actor,key);
            else {var accepted=exports.create(historyDate,registrar,key,actor);run=Db.id(accepted.get("runId"));export=Db.id(accepted.get("exportId"));}
            var done=await(run);active.set(false);var result=new LinkedHashMap<String,Object>();result.put("runId",run);result.put("state",done.get("state"));result.put("counts",done.get("counts"));result.put("businessTime",done.get("businessTime"));result.put("elapsedMillis",(System.nanoTime()-start)/1_000_000);
            assertThat(done.get("state")).as(Objects.toString(done.get("reason"))).isEqualTo("COMPLETED");
            if(done.get("result") instanceof Map<?,?> details){if(details.get("jobId")!=null)jobIds.add(Db.id(details.get("jobId")));for(String name:List.of("snapshotMillis","fileMillis","rowCount","fileBytes"))if(details.containsKey(name))result.put(name,details.get(name));}
            if(export!=0) {
                Path file=exports.file(export);long lines;try(var stream=Files.lines(file)){lines=stream.count();}
                MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=new DigestInputStream(Files.newInputStream(file),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}
                assertThat(lines-1).isEqualTo(10000);assertThat(exports.get(export).get("checksum")).isEqualTo(HexFormat.of().formatHex(digest.digest()));result.put("verifiedRows",lines-1);result.put("checksumVerified",true);
            }
            return result;
        } finally {active.set(false);}
    }

    private Map<String,Object> await(long run) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(4);
        while(System.nanoTime()<deadline){var row=clearing.get(run);if(!Set.of("QUEUED","RUNNING").contains(row.get("state")))return row;Thread.sleep(20);}
        throw new AssertionError("Performance clearing task did not finish: "+run);
    }

    private Session login(Account a) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String csrf=json.readValue(call(client,"", "GET","/auth/csrf",null).body(),Map.class).get("token").toString();
        var response=call(client,csrf,"POST","/auth/login",Map.of("username",a.username(),"password",password));assertThat(response.statusCode()).isEqualTo(200);
        csrf=json.readValue(call(client,"","GET","/auth/csrf",null).body(),Map.class).get("token").toString();return new Session(client,csrf,a);
    }
    private HttpResponse<String> call(HttpClient client,String csrf,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getProperty("local.server.port")+"/api"+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if(!csrf.isEmpty())request.header("X-CSRF-TOKEN",csrf);
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private List<Sample> load(List<Session> sessions,int seconds,String phase,AtomicBoolean active) throws Exception {
        var results=new ArrayList<Future<Sample>>();long start=System.nanoTime();
        try(var executor=Executors.newFixedThreadPool(20)) {
            for(int i=0;i<seconds*5;i++) {
                final int sequence=i,session=i%20,cycle=i/20;long target=start+TimeUnit.MILLISECONDS.toNanos(i*200L);
                long delay=target-System.nanoTime();if(delay>0)TimeUnit.NANOSECONDS.sleep(delay);
                results.add(executor.submit(()-> {
                    Session s=sessions.get(session);int operation=cycle%5;String method="GET",path="/portfolio",name="portfolio";Map<String,Object> body=null;
                    if(operation==1){path="/trading-accounts/"+s.account().trade()+"/wallet";name="wallet";}
                    if(operation>=2){method="POST";boolean credit=operation==2;path="/trading-accounts/"+s.account().trade()+(credit?"/top-ups":"/withdrawals");name=credit?"topup":operation==3?"withdrawal":"withdrawal-retry";body=Map.of("amount","10.00","requestKey",tag+phase+"-"+session+"-"+(cycle/5)+(credit?"C":"D"));}
                    long begin=System.nanoTime();boolean overlap=active.get();int status=0;String error="";
                    try{status=call(s.client(),s.csrf(),method,path,body).statusCode();}catch(Exception ex){error=ex.getClass().getSimpleName();}
                    long end=System.nanoTime();return new Sample(sequence,session,name,status,(end-begin)/1_000_000.0,(end-target)/1_000_000.0,overlap,error);
                }));
            }
            long remaining=start+TimeUnit.SECONDS.toNanos(seconds)-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
            var samples=new ArrayList<Sample>();for(var result:results)samples.add(result.get());return samples;
        }
    }
    private static long heap(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static double percentile(double[] values){if(values.length==0)return 0;Arrays.sort(values);return values[(int)Math.ceil(values.length*.95)-1];}
    private static double p95(List<Sample> samples){return percentile(samples.stream().filter(s->s.status()==200).mapToDouble(Sample::latencyMillis).toArray());}
    private static double p95Scheduled(List<Sample> samples){return percentile(samples.stream().filter(s->s.status()==200).mapToDouble(Sample::scheduledLatencyMillis).toArray());}

    private void verifyAssets() {
        assertThat(db.scalar("""
            SELECT COUNT(*) FROM wallets w JOIN trading_accounts t ON t.id=w.trading_account_id JOIN users u ON u.id=t.user_id
            LEFT JOIN (SELECT trading_account_id,SUM(available_delta) av,SUM(frozen_delta) fr FROM cash_entries GROUP BY trading_account_id) e ON e.trading_account_id=w.trading_account_id
            WHERE u.display_name=? AND (w.available!=COALESCE(e.av,0) OR w.frozen!=COALESCE(e.fr,0) OR w.available<0 OR w.frozen<0)
            """,tag)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(db.scalar("""
            SELECT COUNT(*) FROM position_lots l JOIN users u ON u.id=l.user_id
            LEFT JOIN (SELECT lot_id,SUM(shares_delta) sh,SUM(frozen_delta) fr,SUM(cost_delta) co FROM share_entries GROUP BY lot_id) e ON e.lot_id=l.id
            WHERE u.display_name=? AND (l.shares!=COALESCE(e.sh,0) OR l.frozen_shares!=COALESCE(e.fr,0) OR l.cost!=COALESCE(e.co,0) OR l.shares<l.frozen_shares OR l.frozen_shares<0)
            """,tag)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(db.scalar("SELECT COUNT(*) FROM cash_entries e JOIN users u ON u.id=e.user_id WHERE u.display_name=? AND e.source_type='WITHDRAW'",tag))
            .isEqualByComparingTo(db.scalar("SELECT COUNT(DISTINCT CONCAT(e.trading_account_id,':',e.source_id)) FROM cash_entries e JOIN users u ON u.id=e.user_id WHERE u.display_name=? AND e.source_type='WITHDRAW'",tag));
    }
    private void verifyConfirmations(Map<String,Object> batch) throws Exception {
        for(String query:List.of(
            "SELECT COUNT(*) FROM orders o JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.application_date=? AND o.status='COMPLETED'",
            "SELECT COUNT(*) FROM trade_confirmations c JOIN orders o ON c.source_type='ORDER' AND c.source_id=o.id JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.application_date=?",
            "SELECT COUNT(*) FROM cash_entries e JOIN orders o ON e.source_type='ORDER' AND e.source_id=CAST(o.id AS CHAR) JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.application_date=? AND e.event_type='CONSUME'",
            "SELECT COUNT(*) FROM position_lots l JOIN orders o ON l.source_type='PURCHASE' AND l.source_id=CAST(o.id AS CHAR) JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.application_date=?"))assertThat(db.scalar(query,tag,pendingDate)).isEqualByComparingTo("2000");
        assertThat(db.scalar("SELECT SUM(l.shares) FROM position_lots l JOIN users u ON u.id=l.user_id WHERE u.display_name=?",tag)).isEqualByComparingTo("300000");
        long first=Db.id(batch.get("runId"));assertThat(clearing.submit("CONFIRM",confirmationDate,Map.of(),actor,tag+"CLEARING")).isEqualTo(first);
        long retry=clearing.submit("CONFIRM",confirmationDate,Map.of(),actor,tag+"CLEARING-AGAIN");var done=await(retry);assertThat(done.get("state")).isEqualTo("NO_WORK");
        if(done.get("result") instanceof Map<?,?> result && result.get("jobId")!=null)jobIds.add(Db.id(result.get("jobId")));
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations c JOIN orders o ON c.source_type='ORDER' AND c.source_id=o.id JOIN users u ON u.id=o.user_id WHERE u.display_name=? AND o.application_date=?",tag,pendingDate)).isEqualByComparingTo("2000");
        assertThat(db.scalar("SELECT SUM(l.shares) FROM position_lots l JOIN users u ON u.id=l.user_id WHERE u.display_name=?",tag)).isEqualByComparingTo("300000");verifyAssets();
    }

    private void cleanup() throws Exception {
        if(tag==null)return;
        // Only this fixture's users, registrar and newly inserted dates are removed; foreign keys stay enabled.
        for(var run:db.rows("SELECT id FROM clearing_runs WHERE actor_id=? AND state IN ('QUEUED','RUNNING')",actor))await(Db.id(run.get("id")));
        db.update("DELETE i FROM application_export_items i JOIN application_exports e ON e.id=i.export_id WHERE e.registrar_id=?",registrar);
        db.update("DELETE FROM application_exports WHERE registrar_id=?",registrar);
        db.update("DELETE q FROM clearing_requests q JOIN clearing_runs r ON r.id=q.run_id WHERE r.actor_id=?",actor);
        db.update("DELETE n FROM clearing_run_nodes n JOIN clearing_runs r ON r.id=n.run_id WHERE r.actor_id=?",actor);
        db.update("DELETE FROM clearing_runs WHERE actor_id=?",actor);
        for(long job:jobIds)db.update("DELETE FROM job_runs WHERE id=?",job);
        db.update("DELETE s FROM SPRING_SESSION s JOIN users u ON u.username=s.PRINCIPAL_NAME WHERE u.display_name=?",tag);
        db.update("DELETE b FROM business_number_registry b JOIN orders o ON b.source_type='ORDER' AND b.source_id=o.id JOIN users u ON u.id=o.user_id WHERE u.display_name=?",tag);
        db.update("UPDATE orders o JOIN users u ON u.id=o.user_id SET o.current_confirmation_id=NULL WHERE u.display_name=?",tag);
        db.update("DELETE c FROM trade_confirmations c JOIN orders o ON c.source_type='ORDER' AND c.source_id=o.id JOIN users u ON u.id=o.user_id WHERE u.display_name=?",tag);
        db.update("DELETE e FROM order_events e JOIN orders o ON o.id=e.order_id JOIN users u ON u.id=o.user_id WHERE u.display_name=?",tag);
        for(String table:List.of("notifications","cash_entries","share_entries","position_lots","orders"))db.update("DELETE v FROM "+table+" v JOIN users u ON u.id=v.user_id WHERE u.display_name=?",tag);
        db.update("DELETE b FROM card_bindings b JOIN trading_accounts t ON t.id=b.trading_account_id JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("DELETE b FROM trading_fund_accounts b JOIN trading_accounts t ON t.id=b.trading_account_id JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("DELETE w FROM wallets w JOIN trading_accounts t ON t.id=w.trading_account_id JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("DELETE t FROM trading_accounts t JOIN users u ON u.id=t.user_id WHERE u.display_name=?",tag);
        db.update("DELETE c FROM bank_cards c JOIN users u ON u.id=c.user_id WHERE u.display_name=?",tag);
        db.update("DELETE FROM fund_accounts WHERE registrar_id=?",registrar);
        db.update("DELETE n FROM nav_records n JOIN fund_classes f ON f.id=n.fund_class_id WHERE f.registrar_id=?",registrar);
        db.update("DELETE FROM fund_classes WHERE registrar_id=?",registrar);db.update("DELETE FROM registrars WHERE id=?",registrar);
        db.update("DELETE FROM users WHERE display_name=?",tag);
        if(datesOwned) {
            db.update("DELETE FROM business_number_sequences WHERE business_date BETWEEN ? AND ?",historyDate,confirmationDate);
            db.update("DELETE FROM application_days WHERE business_date BETWEEN ? AND ?",historyDate,confirmationDate);
            db.update("DELETE FROM trading_calendar WHERE date BETWEEN ? AND ?",historyDate,confirmationDate);
        }
        assertThat(db.scalar("SELECT COUNT(*) FROM users WHERE display_name=?",tag)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
