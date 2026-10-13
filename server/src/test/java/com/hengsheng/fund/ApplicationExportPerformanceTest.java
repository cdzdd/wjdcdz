package com.hengsheng.fund;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static org.assertj.core.api.Assertions.*;

/** Opt-in isolated benchmark; all synthetic database rows roll back. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional(isolation=Isolation.READ_COMMITTED)
@EnabledIfSystemProperty(named="export.performance",matches="quick|full")
class ApplicationExportPerformanceTest extends ApplicationExportFixture {
    @Test void measureRealSourcesAndLargePrebuiltSnapshotFiles() throws Exception {
        String profile=System.getProperty("export.performance");
        for(int i=1;i<=1000;i++)order("PURCHASE",i,"SUBMITTED",trade,card,fund,account);
        measure(export,1000,"source-snapshot-and-file");
        int day=1;
        for(int count:profile.equals("full")?new int[]{10_000,100_000}:new int[]{10_000}) {
            var businessDate=date.plusDays(day++);
            db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",businessDate+"T16:00:00");
            db.update("INSERT INTO trading_calendar(date,open) VALUES(?,TRUE) ON DUPLICATE KEY UPDATE open=TRUE",businessDate);
            long id=db.insert("INSERT INTO application_exports(business_date,registrar_id,protocol_version,created_at) VALUES(?,?,'MOCK-TA-APPLICATION-1',NOW())",businessDate,ta);
            long seedStart=System.nanoTime();
            for(int start=1;start<=count;start+=200) {
                var batch=new ArrayList<Object[]>();
                for(int i=start;i<=Math.min(count,start+199);i++) {
                    String key=tag+"P"+count+"-"+i;
                    batch.add(new Object[]{key,user,fund,trade,card,account,businessDate,businessDate.plusDays(1),businessDate.plusDays(2),key,businessDate+" 10:00:00",businessDate.toString().replace("-","")+String.format("%06d",i)});
                }
                db.update("""
                    INSERT INTO orders(order_no,user_id,type,fund_class_id,trading_account_id,source_card_id,binding_version,fund_account_id,status,
                    amount,shares,application_date,confirmation_date,payment_date,request_key,request_fingerprint,purchase_rate,redemption_rate,risk_level,rule_version,cutoff_time,created_at,application_no)
                    VALUES
                    """+String.join(",",Collections.nCopies(batch.size(),"(?,?,'PURCHASE',?,?,?,1,?,'SUBMITTED',123.45,12.3456,?,?,?,?,'performance',0,0,1,1,'15:00:00',?,?)")),batch.stream().flatMap(Arrays::stream).toArray());
            }
            System.out.println("EXPORT_PERF_SEED rows="+count+" sourceSeedMillis="+(System.nanoTime()-seedStart)/1_000_000);
            if(count==100_000)System.out.println("EXPORT_PERF_EXPLAIN "+json.writeValueAsString(db.rows("""
                EXPLAIN SELECT o.id,o.application_no,o.type,o.binding_version,o.amount,o.shares,o.created_at,f.code,f.registrar_id,t.account_no,a.account_no,c.sim_number
                FROM orders o STRAIGHT_JOIN fund_classes f ON f.id=o.fund_class_id LEFT JOIN trading_accounts t ON t.id=o.trading_account_id
                LEFT JOIN fund_accounts a ON a.id=o.fund_account_id LEFT JOIN bank_cards c ON c.id=o.source_card_id
                WHERE o.application_date=? AND f.registrar_id=? AND o.application_no>? AND o.status!='CANCELLED' ORDER BY o.application_no LIMIT 200
                """,businessDate,ta,businessDate.toString().replace("-","")+"050000")));
            measure(id,count,"source-snapshot-and-file");
        }
    }
    private void measure(long id,int expected,String scope) throws Exception {
        System.gc();long baseline=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        AtomicLong peak=new AtomicLong(baseline),snapshotPeak=new AtomicLong(baseline),filePeak=new AtomicLong(),fileBaseline=new AtomicLong(-1);
        var metadata=service.get(id);
        String name="applications_"+metadata.get("businessDate").toString().replace("-","")+"_TA"+ta+"_v1.csv";
        Path part=directory.resolve(name+".part"),complete=directory.resolve(name);
        var sampler=Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(()->{long used=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();peak.accumulateAndGet(used,Math::max);if(Files.exists(part)||Files.exists(complete)){fileBaseline.compareAndSet(-1,used);filePeak.accumulateAndGet(used,Math::max);}else snapshotPeak.accumulateAndGet(used,Math::max);},0,5,TimeUnit.MILLISECONDS);
        Map<String,Object> result;
        try{result=service.generate(id);}finally{sampler.shutdown();sampler.awaitTermination(5,TimeUnit.SECONDS);}
        Path file=service.file(id);MessageDigest sha=MessageDigest.getInstance("SHA-256");long lines=0;
        try(var in=new DigestInputStream(Files.newInputStream(file),sha);var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){while(reader.readLine()!=null)lines++;}
        String checksum=HexFormat.of().formatHex(sha.digest());
        assertThat(lines-1).isEqualTo(expected);assertThat(result.get("rowCount")).isEqualTo((long)expected);
        assertThat(service.get(id).get("checksum")).isEqualTo(checksum);
        var report=new LinkedHashMap<String,Object>(result);
        report.put("scope",scope);report.put("bytes",Files.size(file));report.put("verifiedRows",lines-1);
        report.put("sampledHeapBaselineBytes",baseline);report.put("sampledHeapPeakBytes",peak.get());report.put("sampledHeapIncreaseBytes",peak.get()-baseline);
        report.put("sampledSnapshotHeapPeakBytes",snapshotPeak.get());report.put("sampledSnapshotHeapIncreaseBytes",snapshotPeak.get()-baseline);
        report.put("sampledFileHeapBaselineBytes",fileBaseline.get());report.put("sampledFileHeapPeakBytes",filePeak.get());
        report.put("sampledFileHeapIncreaseBytes",fileBaseline.get()<0?null:filePeak.get()-fileBaseline.get());
        report.put("heapSamplingMillis",5);report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("jvmArguments",ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("EXPORT_PERF "+json.writeValueAsString(report));
    }
}
