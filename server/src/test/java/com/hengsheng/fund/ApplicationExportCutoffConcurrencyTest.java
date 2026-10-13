package com.hengsheng.fund;

import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactions: a pre-cutoff submission must commit before the seal reads its sources. */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationExportCutoffConcurrencyTest {
    @MockitoSpyBean Db db;
    @Autowired TradeService trades;
    @Autowired AccountService accounts;
    @Autowired ClearingService clearing;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @TempDir Path directory;
    final LocalDate date=LocalDate.of(2027,6,7);
    final String tag="EXR"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    long user,card,trade,ta,fund,export;
    String originalClock;
    List<Map<String,Object>> originalDay,originalSequence;
    boolean createdDay;

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",()->{
            String url=System.getenv("DB_TEST_URL");
            if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/(fund_sim_test|fund_sim_upgrade_test)\\?[^\\s]*"))
                throw new IllegalStateException("Cutoff concurrency test requires an explicitly controlled local test database");
            return url;
        });
    }

    @BeforeEach void fixture() {
        assertThat(db.one("SELECT DATABASE() name").get("name")).isIn("fund_sim_test","fund_sim_upgrade_test");
        assertThat(db.one("SELECT open FROM trading_calendar WHERE date=?",date).get("open")).satisfies(value->assertThat(Db.yes(value)).isTrue());
        originalDay=db.rows("SELECT * FROM application_days WHERE business_date=?",date);
        if(!originalDay.isEmpty())assertThat(originalDay.getFirst().get("state")).isEqualTo("OPEN");
        originalSequence=db.rows("SELECT sequence_value FROM business_number_sequences WHERE kind='APPLICATION' AND business_date=?",date);
        originalClock=db.one("SELECT value FROM app_settings WHERE name='demo_clock'").get("value").toString();
        db.update("UPDATE app_settings SET value='2027-06-07T14:59:00' WHERE name='demo_clock'");
        user=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','截止并发',TRUE,5,'2028-12-31')",tag);
        card=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'测试',?,'截止并发',?)",user,tag,tag);
        trade=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,tag,card);
        db.update("INSERT INTO wallets(trading_account_id,available) VALUES(?,1000)",trade);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",trade,card);
        db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,'INITIAL',?,'CREDIT',1000,0,1000,'2027-06-07 14:59:00')",user,trade,tag);
        ta=db.insert("INSERT INTO registrars(name) VALUES(?)",tag);
        fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'截止并发','BOND','测试',?,1)",tag,ta);
        export=db.insert("INSERT INTO application_exports(business_date,registrar_id,protocol_version,created_at) VALUES(?,?,'MOCK-TA-APPLICATION-1',NOW())",date,ta);
        trades.prepareApplicationDay(fund);
        createdDay=originalDay.isEmpty();
    }

    @Test void sealIncludesPreCutoffSubmissionThatWaitsForItsAccount() throws Exception {
        var sharedAcquired=new CountDownLatch(1);
        var exclusiveRequested=new CountDownLatch(1);
        doAnswer(call->{
            String sql=call.getArgument(0);
            if(Thread.currentThread().getName().equals("cutoff-export")&&sql.equals("SELECT state FROM application_days WHERE business_date=? FOR UPDATE"))exclusiveRequested.countDown();
            Object result=call.callRealMethod();
            if(Thread.currentThread().getName().equals("cutoff-submit")&&sql.equals("SELECT state FROM application_days WHERE business_date=? FOR SHARE"))sharedAcquired.countDown();
            return result;
        }).when(db).one(anyString(),any(Object[].class));
        var service=new ApplicationExportService(db,accounts,clearing,manager,json,directory.toString());
        try(var workers=Executors.newFixedThreadPool(2);var locked=Objects.requireNonNull(db.jdbc.getDataSource()).getConnection()) {
            assertThat(locked.getTransactionIsolation()).as("database default used by submission transactions").isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            locked.setAutoCommit(false);
            try(var statement=locked.prepareStatement("SELECT id FROM trading_accounts WHERE id=? FOR UPDATE")) {
                statement.setLong(1,trade);statement.executeQuery().close();
            }
            var submission=workers.submit(()->{
                Thread.currentThread().setName("cutoff-submit");
                return trades.submit(user,Map.of("type","PURCHASE","fundClassId",fund,"tradingAccountId",trade,"bankCardId",card,"bindingVersion",1,"amount","100.00","requestKey",tag));
            });
            Future<Map<String,Object>> generation=null;
            try {
                assertThat(sharedAcquired.await(10,TimeUnit.SECONDS)).as("real Day SHARE lock acquired before account wait").isTrue();
                assertThat(submission.isDone()).isFalse();
                db.update("UPDATE app_settings SET value='2027-06-07T15:10:00' WHERE name='demo_clock'");
                generation=workers.submit(()->{Thread.currentThread().setName("cutoff-export");return service.generate(export);});
                assertThat(exclusiveRequested.await(10,TimeUnit.SECONDS)).as("seal passed eligibility and is requesting Day UPDATE lock").isTrue();
                assertThat(generation.isDone()).isFalse();
            } finally {locked.rollback();}
            var order=submission.get(20,TimeUnit.SECONDS);
            assertThat(order).containsEntry("status","SUBMITTED").containsEntry("applicationDate","2027-06-07");
            assertThat(order.get("createdAt").toString()).isEqualTo("2027-06-07T14:59");
            assertThat(db.one("SELECT available,frozen FROM wallets WHERE trading_account_id=?",trade)).containsEntry("available","900.00").containsEntry("frozen","100.00");
            assertThat(Objects.requireNonNull(generation).get(20,TimeUnit.SECONDS)).containsEntry("rowCount",1L);
            assertThat(service.get(export)).containsEntry("rowCount",1L).containsEntry("amountTotal","100.00");
            var lines=Files.readAllLines(service.file(export));
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1)).contains(","+order.get("applicationNo")+",PURCHASE,").contains(",100.00,,"+tag+",1,2027-06-07T14:59:00,ORDER,"+order.get("id"));
        }
    }

    @AfterEach void cleanup() {
        reset(db);
        if(originalClock==null)return;
        try {
            if(export!=0){db.update("DELETE FROM application_export_items WHERE export_id=?",export);db.update("DELETE FROM application_exports WHERE id=?",export);}
            if(user!=0) {
                db.update("DELETE FROM business_number_registry WHERE source_type='ORDER' AND source_id IN(SELECT id FROM orders WHERE user_id=?)",user);
                db.update("DELETE FROM order_events WHERE order_id IN(SELECT id FROM orders WHERE user_id=?)",user);
                db.update("DELETE FROM notifications WHERE user_id=?",user);
                db.update("DELETE FROM debit_quota_entries WHERE trading_account_id=?",trade);
                db.update("DELETE FROM cash_entries WHERE user_id=?",user);
                db.update("DELETE FROM orders WHERE user_id=?",user);
                for(String table:List.of("trading_fund_accounts","card_bindings","wallets"))db.update("DELETE FROM "+table+" WHERE trading_account_id=?",trade);
                for(String table:List.of("fund_accounts","trading_accounts","bank_cards"))db.update("DELETE FROM "+table+" WHERE user_id=?",user);
                db.update("DELETE FROM users WHERE id=?",user);
            }
            if(fund!=0)db.update("DELETE FROM fund_classes WHERE id=?",fund);
            if(ta!=0)db.update("DELETE FROM registrars WHERE id=?",ta);
            if(createdDay)db.update("DELETE FROM application_days WHERE business_date=?",date);
            else if(!originalDay.isEmpty()) {
                var day=originalDay.getFirst();
                db.update("UPDATE application_days SET state=?,sealed_business_time=?,sealed_at=? WHERE business_date=?",day.get("state"),day.get("sealedBusinessTime"),day.get("sealedAt"),date);
            }
            if(originalSequence.isEmpty())db.update("DELETE FROM business_number_sequences WHERE kind='APPLICATION' AND business_date=? AND sequence_value=1",date);
            else db.update("UPDATE business_number_sequences SET sequence_value=? WHERE kind='APPLICATION' AND business_date=? AND sequence_value=?",originalSequence.getFirst().get("sequenceValue"),date,Db.id(originalSequence.getFirst().get("sequenceValue"))+1);
        } finally {db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",originalClock);}
    }
}
