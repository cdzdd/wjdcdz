package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BusinessNumberIntegrationTest {
    @Autowired Db db;
    @Autowired TradeService trades;
    @Autowired ConversionService conversions;
    @Autowired BusinessNumberService numbers;
    @Autowired ConfirmationService confirmations;
    @Autowired AccountService accounts;
    @Autowired PlatformTransactionManager manager;
    @org.springframework.test.context.transaction.BeforeTransaction void prepareAdmissionMetadata(){
        // These tests open an outer rollback transaction, unlike HTTP submission's preparation phase.
        for(String date:java.util.List.of("2026-10-12","2026-10-13","2026-10-14","2026-10-15","2026-10-16","2026-10-19"))db.update("INSERT IGNORE INTO application_days(business_date,state) VALUES(?,'OPEN')",date);
    }
    long user;
    Map<String,Object> card() {
        String key=UUID.randomUUID().toString();
        if(user==0) user=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','编号测试',TRUE,5,'2027-12-31')","numbers"+key);
        long cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'编号测试',?)",user,key,key);
        long tid=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,key,cid);
        db.update("INSERT INTO wallets(trading_account_id,available) VALUES(?,100000)",tid);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",tid,cid);
        return Map.of("id",cid,"tid",tid);
    }
    void clock(String at) {db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",at);}
    Map<String,Object> purchase(Map<String,Object> c,String key) {
        return Map.of("type","PURCHASE","fundClassId",1,"tradingAccountId",c.get("tid"),"bankCardId",c.get("id"),"bindingVersion",1,"amount","100.00","requestKey",key);
    }
    @Test @Transactional void dailyNumbersAreUniqueAcrossAccountsAndTypes() {
        clock("2026-10-12T14:00:00");var a=card();var b=card();
        var first=trades.submit(user,purchase(a,"first"));var second=trades.submit(user,purchase(b,"second"));
        assertThat(first.get("applicationNo")).as("新申请应得到日期加六位序列编号").isNotNull();
        long fa=accounts.linkFundAccount(user,Db.id(a.get("tid")),1);
        trades.addLot(user,Db.id(a.get("tid")),1,Db.id(a.get("id")),fa,new BigDecimal("20"),new BigDecimal("20"),LocalDate.of(2026,9,1),"TEST","seed");
        db.update("INSERT INTO conversion_pairs(source_class_id,target_class_id,enabled) VALUES(1,2,TRUE) ON DUPLICATE KEY UPDATE enabled=TRUE");
        var conversion=conversions.submit(user,Map.of("tradingAccountId",a.get("tid"),"sourceClassId",1,"targetClassId",2,"shares","10.0000","requestKey","convert"));
        String number=first.get("applicationNo").toString();
        assertThat(number).matches("20261012[0-9]{6}");
        assertThat(Long.parseLong(second.get("applicationNo").toString())).isEqualTo(Long.parseLong(number)+1);
        assertThat(Long.parseLong(conversion.get("applicationNo").toString())).isEqualTo(Long.parseLong(number)+2);
    }
    @Test @Transactional void retryAndWaitingDoNotCreateAnotherNumber() {
        clock("2026-10-12T14:00:00");var a=card();var input=purchase(a,"retry");
        var first=trades.submit(user,input);long oid=Db.id(first.get("id"));
        assertThat(trades.submit(user,input).get("applicationNo")).isEqualTo(first.get("applicationNo"));
        assertThat(trades.confirmOne(oid)).isFalse();
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",oid)).isEqualByComparingTo("0");
        db.update("DELETE FROM nav_records WHERE fund_class_id=1 AND date='2026-10-12'");
        clock("2026-10-13T09:00:00");assertThat(trades.confirmOne(oid)).isFalse();
        assertThat(trades.order(user,oid).get("currentConfirmationId")).isNull();
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(1,'2026-10-12',1.2,TRUE)");
        assertThat(trades.confirmOne(oid)).isTrue();var confirmed=trades.order(user,oid);
        assertThat(confirmed.get("confirmationNo")).as("成功确认拥有独立确认编号").isNotNull();
        assertThat(confirmed.get("confirmationNo").toString()).matches("20261013[0-9]{6}");
        assertThat(trades.confirmOne(oid)).isFalse();
        long confirmation=Db.id(confirmed.get("currentConfirmationId"));
        assertThat(confirmations.record("ORDER",oid,0,LocalDate.of(2026,10,13),LocalDate.of(2026,10,13),Map.of("ignoredRetry",true))).isEqualTo(confirmation);
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",oid)).isEqualByComparingTo("1");
        assertThat(db.one("SELECT result,state,effective_date FROM trade_confirmations WHERE id=?",confirmation)).containsEntry("state","ACTIVE").containsEntry("effectiveDate","2026-10-13");
        assertThat(db.one("SELECT result FROM trade_confirmations WHERE id=?",confirmation).get("result").toString()).contains("COMPLETED");
    }
    @Test void concurrentNumbersAreUnique() throws Exception {
        var day=LocalDate.of(2027,4,12);var work=new ArrayList<java.util.concurrent.Callable<String>>();
        for(int i=0;i<24;i++)work.add(()->numbers.next("APPLICATION",day));
        try(var executor=Executors.newFixedThreadPool(6)) {
            var actual=new ArrayList<String>();for(var future:executor.invokeAll(work))actual.add(future.get());
            assertThat(actual).doesNotHaveDuplicates().allMatch(n->n.matches("20270412[0-9]{6}"));
        }
    }
    @Test @Transactional void cutoffAndNonWorkingDaysUseApplicationBusinessDate() {
        var a=card();clock("2026-10-12T15:00:00");
        assertThat(trades.submit(user,purchase(a,"cutoff")).get("applicationNo").toString()).startsWith("20261013");
        clock("2026-10-17T14:00:00");
        assertThat(trades.submit(user,purchase(a,"weekend")).get("applicationNo").toString()).startsWith("20261019");
        assertThatThrownBy(()->numbers.next("APPLICATION",LocalDate.of(2026,10,17))).isInstanceOf(ApiException.class);
    }
    @Test @Transactional void sequenceLimitNeverWraps() {
        var day=LocalDate.of(2027,4,13);
        db.update("INSERT INTO business_number_sequences(kind,business_date,sequence_value) VALUES('APPLICATION',?,999998)",day);
        assertThat(numbers.next("APPLICATION",day)).isEqualTo("20270413999999");
        assertThatThrownBy(()->numbers.next("APPLICATION",day)).isInstanceOf(ApiException.class).hasMessageContaining("用尽");
        assertThat(db.scalar("SELECT sequence_value FROM business_number_sequences WHERE kind='APPLICATION' AND business_date=?",day)).isEqualByComparingTo("999999");
    }
    @Test @Transactional void confirmationWaitsThroughClosedDayWithoutFutureNumber(){
        var a=card();clock("2026-10-16T14:00:00");var order=trades.submit(user,purchase(a,"closed-confirm"));long oid=Db.id(order.get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(1,'2026-10-16',1.2,TRUE) ON DUPLICATE KEY UPDATE nav=1.2,published=TRUE");
        db.update("UPDATE orders SET confirmation_date='2026-10-16',planned_confirmation_date='2026-10-16' WHERE id=?",oid);
        clock("2026-10-17T09:00:00");assertThat(trades.confirmOne(oid)).isFalse();
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",oid)).isEqualByComparingTo("0");
        clock("2026-10-19T09:00:00");assertThat(trades.confirmOne(oid)).isTrue();
        assertThat(trades.order(user,oid).get("confirmationNo").toString()).startsWith("20261019");
    }
    @Test @Transactional void legacyRecordsRemainReadable() {
        clock("2026-10-12T14:00:00");var a=card();var order=trades.submit(user,purchase(a,"legacy"));long oid=Db.id(order.get("id"));
        db.update("UPDATE orders SET application_no=NULL WHERE id=?",oid);
        var before=db.one("SELECT available,frozen FROM wallets WHERE trading_account_id=?",a.get("tid"));
        var read=trades.order(user,oid);
        assertThat(read.get("applicationNo")).isNull();assertThat(read.get("orderNo")).isEqualTo(order.get("orderNo"));
        assertThat(read.get("confirmedAt")).isNull();assertThat(read.get("confirmationNo")).isNull();
        assertThat(db.one("SELECT available,frozen FROM wallets WHERE trading_account_id=?",a.get("tid"))).isEqualTo(before);
    }
    @Test void failedTransactionDoesNotLeaveEffectiveOrphanRecords() {
        var beforeRegistry=db.scalar("SELECT COUNT(*) FROM business_number_registry");
        var beforeConfirmations=db.scalar("SELECT COUNT(*) FROM trade_confirmations");
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(tx->{
            clock("2026-10-12T14:00:00");var a=card();var order=trades.submit(user,purchase(a,"rollback"));
            db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(1,'2026-10-12',1.2,TRUE) ON DUPLICATE KEY UPDATE nav=1.2,published=TRUE");
            clock("2026-10-13T09:00:00");assertThat(trades.confirmOne(Db.id(order.get("id")))).isTrue();
            throw new IllegalStateException("回滚确认事务");
        })).isInstanceOf(IllegalStateException.class).hasMessage("回滚确认事务");
        assertThat(db.scalar("SELECT COUNT(*) FROM business_number_registry")).isEqualByComparingTo(beforeRegistry);
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations")).isEqualByComparingTo(beforeConfirmations);
    }
    @Test @Transactional void kindsAndNextDayHaveIndependentSequences() {
        var day=LocalDate.of(2027,4,14);
        assertThat(numbers.next("APPLICATION",day)).isEqualTo("20270414000001");
        assertThat(numbers.next("CONFIRMATION",day)).isEqualTo("20270414000001");
        assertThat(numbers.next("APPLICATION",day.plusDays(1))).isEqualTo("20270415000001");
    }
}
