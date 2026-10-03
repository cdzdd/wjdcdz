package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class OfferingSipRegressionTest {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired OfferingService offerings;
    @Autowired SipService sip;
    @Autowired FundService funds;
    @Autowired PortfolioService portfolio;
    long user,account,card,fund;
    String key;
    @BeforeEach void fixture() {
        assertThat(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB='fund_sim_test'")).isEqualByComparingTo("1");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","", "ROLE_ADMIN"));
        clock("2026-10-14T10:00:00");key=UUID.randomUUID().toString().replace("-","").substring(0,20);
        user=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','认购定投测试',TRUE,5,'2027-10-14')","os"+key);
        fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,state,min_purchase) VALUES(?,'认购定投测试','BOND','模拟管理人',1,1,'RAISING',1)","OS"+key);
        card=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'认购定投测试',?)",user,"SIM-"+key,key);
        account=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,"OS-"+key,card);
        db.update("INSERT INTO wallets(trading_account_id,available) VALUES(?,2000)",account);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",account,card);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void clock(String value){db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",value);}
    Map<String,Object> purchase(){return new HashMap<>(Map.of("fundClassId",fund,"tradingAccountId",account,"bankCardId",card,"bindingVersion",1,"amount","1200.00","requestKey",key));}
    Map<String,Object> outcome(String end,String nav){return Map.of("outcome","ESTABLISHED","endDate",end,"initialNav",nav,"interestRate","0");}
    Map<String,Object> plan(String frequency,int day,String start){var value=purchase();value.put("amount","100.00");value.put("frequency",frequency);value.put("day",day);value.put("startDate",start);return value;}

    @Test void earlierOfferingEndDateIsRejectedBeforeFixingAnIrrecoverableEvent() {
        offerings.subscribe(user,purchase());
        assertThatThrownBy(()->offerings.finalizeOffering(fund,outcome("2026-10-13","1"))).isInstanceOf(RuntimeException.class);
        assertThat(db.scalar("SELECT COUNT(*) FROM offering_events WHERE fund_class_id=?",fund)).isZero();
        assertThat(offerings.finalizeOffering(fund,outcome("2026-10-14","1")).get("processed")).isEqualTo(1);
    }
    @Test void establishmentPersistsActualInitialNavAndDuplicateResultCannotReviveAClosedFund() {
        var order=offerings.subscribe(user,purchase());clock("2026-10-16T10:00:00");
        var result=outcome("2026-10-15","2");offerings.finalizeOffering(fund,result);
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE fund_class_id=?",fund)).isEqualByComparingTo("600");
        assertThat(Db.decimal(funds.fund(fund).get("nav"))).isEqualByComparingTo("2");
        assertThat(portfolio.portfolio(user).get("totalAssets")).isEqualTo("2000.00");
        assertThat(db.one("SELECT acquired_date FROM position_lots WHERE fund_class_id=?",fund).get("acquiredDate").toString()).isEqualTo("2026-10-16");
        assertThat(db.one("SELECT confirmation_date FROM orders WHERE id=?",order.get("id")).get("confirmationDate").toString()).isEqualTo("2026-10-16");
        for(String state:new String[]{"OPEN","TERMINATED","LIQUIDATED"}) {
            db.update("UPDATE fund_classes SET state=? WHERE id=?",state,fund);
            assertThat(offerings.finalizeOffering(fund,result).get("processed")).isEqualTo(0);
            assertThat(db.one("SELECT state FROM fund_classes WHERE id=?",fund).get("state")).isEqualTo(state);
        }
    }
    @Test void subscriptionSnapshotsFixedFeeTierAndFingerprintWithoutAllowingAnInvalidNetAmount() {
        db.update("UPDATE fund_classes SET purchase_rate=0.015 WHERE id=?",fund);
        db.update("INSERT INTO fee_tiers(fund_class_id,min_amount,rate,fixed_fee) VALUES(?,1000,0.03,100)",fund);
        var request=purchase();var order=offerings.subscribe(user,request);
        assertThat(Db.decimal(order.get("fixedFee"))).isEqualByComparingTo("100");
        assertThat(order.get("requestFingerprint").toString()).hasSize(64);
        var changed=new HashMap<>(request);changed.put("bindingVersion",2);
        assertThatThrownBy(()->offerings.subscribe(user,changed)).isInstanceOf(RuntimeException.class);
        db.update("UPDATE fee_tiers SET fixed_fee=1100 WHERE fund_class_id=?",fund);
        offerings.finalizeOffering(fund,outcome("2026-10-14","2"));
        assertThat(db.scalar("SELECT fee FROM orders WHERE id=?",order.get("id"))).isEqualByComparingTo("100");
        assertThat(db.scalar("SELECT shares FROM orders WHERE id=?",order.get("id"))).isEqualByComparingTo("550");
        assertThat(offerings.subscribe(user,request).get("id")).isEqualTo(order.get("id"));
    }
    @Test void fixedFeeAtLeastPrincipalNeverDebitsTheSubscriptionAccount() {
        db.update("INSERT INTO fee_tiers(fund_class_id,min_amount,rate,fixed_fee) VALUES(?,0,0,1200)",fund);
        assertThatThrownBy(()->offerings.subscribe(user,purchase())).isInstanceOf(RuntimeException.class);
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",account)).isEqualByComparingTo("2000");
        assertThat(db.scalar("SELECT COUNT(*) FROM offering_orders WHERE fund_class_id=?",fund)).isZero();
    }
    @Test void aFixedProcessingResultClosesSubscriptionAndLegacyFingerprintsRemainRetryable() {
        var request=purchase();var order=offerings.subscribe(user,request);
        db.update("UPDATE orders SET request_fingerprint='legacy-key' WHERE id=?",order.get("id"));
        assertThat(offerings.subscribe(user,request).get("id")).isEqualTo(order.get("id"));
        db.insert("INSERT INTO offering_events(fund_class_id,outcome,end_date,initial_nav,interest_rate,created_at) VALUES(?,'ESTABLISHED','2026-10-14',1,0,?)",fund,accounts.clock());
        var fresh=new HashMap<>(request);fresh.put("requestKey",key+"next");fresh.put("amount","100.00");
        assertThatThrownBy(()->offerings.subscribe(user,fresh)).isInstanceOf(RuntimeException.class);
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",account)).isEqualByComparingTo("800");
    }
    @Test void restartRunsOnlyLatestMissedSipPeriodAtTheActualExecutionDateAndDoesNotRepeatFailure() {
        db.update("UPDATE fund_classes SET state='OPEN' WHERE id=?",fund);clock("2026-10-01T10:00:00");
        var created=sip.create(user,plan("WEEKLY",1,"2026-10-01"));clock("2026-10-21T10:00:00");
        sip.run(LocalDate.of(2026,10,21));sip.run(LocalDate.of(2026,10,21));
        var runs=db.rows("SELECT * FROM sip_runs WHERE plan_id=?",created.get("id"));assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().get("scheduledDate").toString()).isEqualTo("2026-10-19");
        assertThat(runs.getFirst().get("executionDate").toString()).isEqualTo("2026-10-21");
        assertThat(db.one("SELECT application_date FROM orders WHERE id=?",runs.getFirst().get("orderId")).get("applicationDate").toString()).isEqualTo("2026-10-21");
        db.update("UPDATE orders SET status='CANCELLED' WHERE id=?",runs.getFirst().get("orderId"));
        db.update("UPDATE wallets SET available=0,frozen=0 WHERE trading_account_id=?",account);
        clock("2026-10-28T10:00:00");sip.run(LocalDate.of(2026,10,28));
        assertThat(db.one("SELECT status FROM sip_runs WHERE plan_id=? AND scheduled_date='2026-10-26'",created.get("id")).get("status")).isEqualTo("FAILED");
        db.update("UPDATE wallets SET available=1000 WHERE trading_account_id=?",account);sip.run(LocalDate.of(2026,10,28));
        assertThat(db.scalar("SELECT COUNT(*) FROM orders WHERE request_key=?","SIP-"+created.get("id")+"-2026-10-26")).isZero();
    }
    @Test void resumingSipDoesNotChargePausedPeriods() {
        db.update("UPDATE fund_classes SET state='OPEN' WHERE id=?",fund);clock("2026-10-01T10:00:00");
        var created=sip.create(user,plan("WEEKLY",1,"2026-10-01"));sip.change(user,Db.id(created.get("id")),Map.of("status","PAUSED"));
        clock("2026-10-21T10:00:00");sip.change(user,Db.id(created.get("id")),Map.of("status","ACTIVE"));
        sip.run(LocalDate.of(2026,10,21));assertThat(db.scalar("SELECT COUNT(*) FROM sip_runs WHERE plan_id=?",created.get("id"))).isZero();
        clock("2026-10-26T10:00:00");sip.run(LocalDate.of(2026,10,26));
        assertThat(db.scalar("SELECT COUNT(*) FROM sip_runs WHERE plan_id=?",created.get("id"))).isEqualByComparingTo("1");
    }
}
