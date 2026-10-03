package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Propagation;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class MoneyLifecycleIntegrationTest {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired TradeService trades;
    @Autowired MoneyIncomeService money;
    @Autowired LifecycleService lifecycle;
    long user, fund, cardA, cardB, accountA, accountB, fundAccount;
    String key;

    @BeforeEach void fixture() {
        assertThat(db.jdbc.getDataSource()).isNotNull();
        assertThat(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB='fund_sim_test'")).isEqualByComparingTo("1");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","", "ROLE_ADMIN"));
        db.update("UPDATE app_settings SET value='2026-10-14T10:00:00' WHERE name='demo_clock'");
        key=UUID.randomUUID().toString().replace("-","").substring(0,20);
        user=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','收益测试',TRUE,5,'2027-10-14')","ml"+key);
        fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,min_purchase,min_redemption) VALUES(?,'收益清盘测试','MONEY','模拟管理人',1,1,1,1)","ML"+key);
        cardA=card("A");cardB=card("B");accountA=account(cardA,"A");accountB=account(cardB,"B");
        fundAccount=accounts.linkFundAccount(user,accountA,fund);accounts.linkFundAccount(user,accountB,fund);
    }
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    long card(String name){return db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'收益测试',?)",user,"SIM-"+key+name,key+name);}
    long account(long card,String name){long id=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,"ML-"+key+name,card);db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",id);db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",id,card);return id;}
    long lot(long account,long card,String shares,LocalDate date){return trades.addLot(user,account,fund,card,fundAccount,new BigDecimal(shares),new BigDecimal(shares),date,"TEST_LOT",key+account);}
    void rate(String day,String income){money.write(Map.of("fundClassId",fund,"date",day,"incomePerTenThousand",income,"annualYield","777.00"));}
    BigDecimal shares(long account){return db.scalar("SELECT COALESCE(SUM(shares),0) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",account,fund);}

    @Test void weekendsCompoundOnNextNaturalDayAndKeepSourceTailsWithoutUsingAnnualYield() {
        lot(accountA,cardA,"10000.0000",LocalDate.of(2026,10,9));
        lot(accountB,cardB,"5000.0000",LocalDate.of(2026,10,9));
        rate("2026-10-10","1");rate("2026-10-11","1");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(2);
        assertThat(shares(accountA)).isEqualByComparingTo("10001.0000");
        assertThat(shares(accountB)).isEqualByComparingTo("5000.5000");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(0);
        assertThat(money.run(LocalDate.of(2026,10,11)).get("processed")).isEqualTo(2);
        assertThat(shares(accountA)).isEqualByComparingTo("10002.0001");
        assertThat(shares(accountB)).isEqualByComparingTo("5001.0000");
        assertThat(db.scalar("SELECT tail FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-11'",accountB)).isEqualByComparingTo("0.000050000000");
        assertThatThrownBy(()->rate("2026-10-10","2")).isInstanceOf(ApiException.class);
    }
    @Test void historicEligibilityUsesAcquiredDateAndEntriesEvenAfterTheOriginalLotWasRedeemed() {
        lot(accountA,cardA,"10000",LocalDate.of(2026,10,9));
        db.update("UPDATE app_settings SET value='2026-10-12T10:00:00' WHERE name='demo_clock'");
        trades.consumeLots(trades.reserveLots(user,accountA,fund,new BigDecimal("10000")),"TEST_REDEMPTION",key);
        db.update("UPDATE app_settings SET value='2026-10-14T10:00:00' WHERE name='demo_clock'");
        rate("2026-10-10","1");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(1);
        assertThat(shares(accountA)).isZero();
        assertThat(db.scalar("SELECT amount FROM payouts WHERE source_type='MONEY_EXIT_INCOME' AND trading_account_id=?",accountA)).isEqualByComparingTo("1.00");
        assertThat(db.scalar("SELECT shares FROM money_income_snapshots WHERE fund_class_id=?",fund)).isEqualByComparingTo("10000");
        lot(accountB,cardB,"5000",LocalDate.of(2026,10,12));
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(0);
        assertThat(db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE trading_account_id=?",accountB)).isZero();
    }
    @Test void missingDayBlocksLaterCompoundingAndNegativeIncomeNeverConsumesFrozenShares() {
        long original=lot(accountA,cardA,"1000",LocalDate.of(2026,10,9));
        rate("2026-10-11","1");
        assertThat(money.run(LocalDate.of(2026,10,11)).get("waiting")).isEqualTo(1);
        assertThat(shares(accountA)).isEqualByComparingTo("1000");
        rate("2026-10-10","-10");
        var reserved=trades.reserveLots(user,accountA,fund,new BigDecimal("1000"));
        assertThat(money.run(LocalDate.of(2026,10,10)).get("waiting")).isEqualTo(1);
        assertThat(db.scalar("SELECT frozen_shares FROM position_lots WHERE id=?",original)).isEqualByComparingTo("1000");
        assertThat(shares(accountA)).isEqualByComparingTo("1000");
        trades.releaseLots(reserved);
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(1);
        assertThat(shares(accountA)).isEqualByComparingTo("999.0000");
        assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",original)).isEqualByComparingTo("1000.00");
    }
    @Test void negativeIncomeAfterFullRedemptionWaitsForCashAndSettlesOnlyTheSourceAccount() {
        lot(accountA,cardA,"1000",LocalDate.of(2026,10,9));
        db.update("UPDATE app_settings SET value='2026-10-12T10:00:00' WHERE name='demo_clock'");
        trades.consumeLots(trades.reserveLots(user,accountA,fund,new BigDecimal("1000")),"TEST_REDEMPTION",key);
        rate("2026-10-10","-1");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("waiting")).isEqualTo(1);
        accounts.credit(accountA,"TEST_REFUND",key,new BigDecimal("1000"));
        accounts.credit(accountB,"TEST_OTHER",key,new BigDecimal("500"));
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(1);
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountA)).isEqualByComparingTo("999.90");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountB)).isEqualByComparingTo("500");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(0);
    }
    @Test void liquidationConsumesBothAccountsAndRetainsBlockedOriginPayoutsWithoutDuplicatePayment() {
        db.update("UPDATE fund_classes SET type='STOCK' WHERE id=?",fund);
        lot(accountA,cardA,"1000",LocalDate.of(2026,10,9));lot(accountB,cardB,"500",LocalDate.of(2026,10,9));
        db.update("INSERT INTO sip_plans(user_id,trading_account_id,bank_card_id,binding_version,fund_class_id,amount,frequency,day,start_date,request_key,created_at) VALUES(?,?,?,1,?,100,'MONTHLY',10,'2026-10-01',?,?)",user,accountA,cardA,fund,key,accounts.clock());
        lifecycle.terminate(fund,Map.of("reason","课程清盘"));
        assertThat(db.one("SELECT state,purchase_open,redemption_open FROM fund_classes WHERE id=?",fund).get("state")).isEqualTo("TERMINATED");
        assertThat(db.one("SELECT status FROM sip_plans WHERE fund_class_id=?",fund).get("status")).isEqualTo("TERMINATED");
        var input=Map.<String,Object>of("fundClassId",fund,"perShare","1.05","date","2026-10-14","reason","教学分配");
        var event=lifecycle.liquidate(input);lifecycle.liquidate(input);
        assertThat(shares(accountA)).isZero();assertThat(shares(accountB)).isZero();
        assertThat(db.scalar("SELECT COUNT(*) FROM liquidation_items WHERE event_id=?",event.get("id"))).isEqualByComparingTo("2");
        assertThat(db.scalar("SELECT amount FROM liquidation_items WHERE event_id=? AND trading_account_id=?",event.get("id"),accountA)).isEqualByComparingTo("1050");
        assertThat(db.scalar("SELECT amount FROM liquidation_items WHERE event_id=? AND trading_account_id=?",event.get("id"),accountB)).isEqualByComparingTo("525");
        db.update("UPDATE bank_cards SET receive_state='BLOCKED' WHERE id=?",cardA);
        long payoutA=Db.id(db.one("SELECT id FROM payouts WHERE source_type='LIQUIDATION' AND trading_account_id=?",accountA).get("id"));
        long payoutB=Db.id(db.one("SELECT id FROM payouts WHERE source_type='LIQUIDATION' AND trading_account_id=?",accountB).get("id"));
        assertThat(trades.payOne(payoutA)).isFalse();assertThat(trades.payOne(payoutB)).isTrue();
        assertThat(db.one("SELECT status FROM payouts WHERE id=?",payoutA).get("status")).isEqualTo("BLOCKED");
        db.update("UPDATE bank_cards SET receive_state='RECEIVABLE' WHERE id=?",cardA);
        assertThat(trades.payOne(payoutA)).isTrue();assertThat(trades.payOne(payoutA)).isFalse();
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountA)).isEqualByComparingTo("1050");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountB)).isEqualByComparingTo("525");
        assertThatThrownBy(()->lifecycle.liquidate(Map.of("fundClassId",fund,"perShare","2","date","2026-10-14","reason","改价"))).isInstanceOf(ApiException.class);
    }
    @Test void liquidationRejectsUnsettledMoneyAndFrozenHoldings() {
        lot(accountA,cardA,"1000",LocalDate.of(2026,10,9));
        lifecycle.terminate(fund,Map.of("reason","停止交易"));
        var input=Map.<String,Object>of("fundClassId",fund,"perShare","1","date","2026-10-14","reason","清盘");
        assertThatThrownBy(()->lifecycle.liquidate(input)).isInstanceOf(ApiException.class);
        assertThat(shares(accountA)).isEqualByComparingTo("1000");
        assertThat(db.scalar("SELECT COUNT(*) FROM liquidation_events WHERE fund_class_id=?",fund)).isZero();
        db.update("UPDATE fund_classes SET type='STOCK' WHERE id=?",fund);
        trades.reserveLots(user,accountA,fund,new BigDecimal("100"));
        assertThatThrownBy(()->lifecycle.liquidate(input)).isInstanceOf(ApiException.class);
        assertThat(shares(accountA)).isEqualByComparingTo("1000");
    }
    @Test void incomeWaitsUntilTheNaturalDayHasEndedAtTenMinutesPastMidnight() {
        lot(accountA,cardA,"10000",LocalDate.of(2026,10,9));rate("2026-10-10","1");
        db.update("UPDATE app_settings SET value='2026-10-11T00:09:00' WHERE name='demo_clock'");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("waiting")).isEqualTo(1);
        assertThat(shares(accountA)).isEqualByComparingTo("10000");
        db.update("UPDATE app_settings SET value='2026-10-11T00:10:00' WHERE name='demo_clock'");
        assertThat(money.run(LocalDate.of(2026,10,10)).get("processed")).isEqualTo(1);
        assertThat(shares(accountA)).isEqualByComparingTo("10001");
    }
    @Test void sameAccountLiquidationPreservesTwoHistoricalCardsAndTheApprovedReplacementRoute() {
        db.update("UPDATE fund_classes SET type='STOCK' WHERE id=?",fund);
        lot(accountA,cardA,"1000",LocalDate.of(2026,10,9));
        long replacement=card("C");
        long approval=db.insert("INSERT INTO card_change_requests(user_id,trading_account_id,old_card_id,new_card_id,old_binding_version,new_binding_version,status,reason,request_key,created_at) VALUES(?,?,?,?,1,2,'APPROVED','模拟批准换卡',?,?)",user,accountA,cardA,replacement,key,accounts.clock());
        db.update("UPDATE trading_accounts SET bank_card_id=?,binding_version=2 WHERE id=?",replacement,accountA);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version,change_request_id) VALUES(?,?,2,?)",accountA,replacement,approval);
        lot(accountA,replacement,"500",LocalDate.of(2026,10,12));
        lifecycle.terminate(fund,Map.of("reason","来源清盘"));
        var event=lifecycle.liquidate(Map.of("fundClassId",fund,"perShare","1.05","date","2026-10-14","reason","保留来源"));
        assertThat(db.scalar("SELECT COUNT(*) FROM liquidation_items WHERE event_id=? AND trading_account_id=?",event.get("id"),accountA)).isEqualByComparingTo("2");
        var oldRoute=db.one("SELECT * FROM payouts WHERE source_type='LIQUIDATION' AND trading_account_id=? AND source_card_id=?",accountA,cardA);
        assertThat(Db.id(oldRoute.get("targetCardId"))).isEqualTo(replacement);
        assertThat(Db.id(oldRoute.get("authorizationId"))).isEqualTo(approval);
        assertThat(Db.decimal(oldRoute.get("amount"))).isEqualByComparingTo("1050");
        assertThat(db.scalar("SELECT amount FROM payouts WHERE source_type='LIQUIDATION' AND trading_account_id=? AND source_card_id=?",accountA,replacement)).isEqualByComparingTo("525");
    }
    @Test void terminationCompletesLegacyTerminatedMetadataWithoutChangingTheFirstTerminationTime() {
        db.update("UPDATE fund_classes SET state='TERMINATED' WHERE id=?",fund);
        var closed=lifecycle.terminate(fund,Map.of("reason","完成终止手续"));
        assertThat(closed.get("terminatedAt").toString()).isEqualTo("2026-10-14T10:00");
        assertThat(Db.yes(closed.get("purchaseOpen"))).isFalse();
        db.update("UPDATE app_settings SET value='2026-10-15T10:00:00' WHERE name='demo_clock'");
        assertThat(lifecycle.terminate(fund,Map.of("reason","重复终止")).get("terminatedAt")).isEqualTo(closed.get("terminatedAt"));
    }
    @Test void liquidationRecordsAndClosesMoneyTailSoThePaidEmptyAccountCanBeUnbound() {
        lot(accountA,cardA,"5000",LocalDate.of(2026,10,9));rate("2026-10-10","1");rate("2026-10-11","1");
        money.run(LocalDate.of(2026,10,11));
        long snapshot=Db.id(db.one("SELECT id FROM money_income_snapshots WHERE fund_class_id=? AND date='2026-10-11'",fund).get("id"));
        assertThat(db.scalar("SELECT tail FROM money_income_snapshots WHERE id=?",snapshot)).isEqualByComparingTo("0.00005");
        db.update("UPDATE app_settings SET value='2026-10-12T10:00:00' WHERE name='demo_clock'");
        lifecycle.terminate(fund,Map.of("reason","收束尾差"));
        var input=Map.<String,Object>of("fundClassId",fund,"perShare","1","date","2026-10-12","reason","清盘收尾");
        var event=lifecycle.liquidate(input);
        assertThat(db.scalar("SELECT tail FROM money_income_snapshots WHERE id=?",snapshot)).isZero();
        assertThat(db.scalar("SELECT income FROM money_income_snapshots WHERE id=?",snapshot)).isEqualByComparingTo("0.50005");
        assertThat(db.scalar("SELECT original_tail FROM money_tail_disposals WHERE snapshot_id=?",snapshot)).isEqualByComparingTo("0.00005");
        assertThat(db.scalar("SELECT settled_amount FROM money_tail_disposals WHERE snapshot_id=?",snapshot)).isZero();
        lifecycle.liquidate(input);
        assertThat(db.scalar("SELECT COUNT(*) FROM money_tail_disposals WHERE event_id=?",event.get("id"))).isEqualByComparingTo("1");
        long payout=Db.id(db.one("SELECT id FROM payouts WHERE source_type='LIQUIDATION' AND trading_account_id=?",accountA).get("id"));
        assertThat(trades.payOne(payout)).isTrue();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ml"+key,"", "ROLE_INVESTOR"));
        accounts.cashOperation(accountA,Map.of("amount","5001.00","requestKey","tail-withdraw"),false);
        assertThatCode(()->accounts.unbind(cardA,Map.of())).doesNotThrowAnyException();
    }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void tailDisposalRoundsHalfUpPerSourceAndRejectsLossWithoutSourceCash() {
        for(var source:Map.of(accountA,cardA,accountB,cardB).entrySet())
            db.update("INSERT INTO money_income_snapshots(date,user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,income,tail,status) VALUES('2026-10-13',?,?,?,?,?,0,0,?,'COMPLETED')",user,source.getKey(),fund,source.getValue(),fundAccount,source.getKey()==accountA?"0.005":"-0.005");
        lifecycle.terminate(fund,Map.of("reason","尾差最终结算"));
        var input=Map.<String,Object>of("fundClassId",fund,"perShare","1","date","2026-10-14","reason","最终舍入");
        assertThatThrownBy(()->lifecycle.liquidate(input)).isInstanceOf(ApiException.class);
        assertThat(db.scalar("SELECT COUNT(*) FROM liquidation_events WHERE fund_class_id=?",fund)).isZero();
        accounts.credit(accountB,"TEST_TAIL_CASH",key,new BigDecimal("0.01"));
        var event=lifecycle.liquidate(input);lifecycle.liquidate(input);
        assertThat(db.scalar("SELECT settled_amount FROM money_tail_disposals WHERE event_id=? AND trading_account_id=?",event.get("id"),accountA)).isEqualByComparingTo("0.01");
        assertThat(db.scalar("SELECT settled_amount FROM money_tail_disposals WHERE event_id=? AND trading_account_id=?",event.get("id"),accountB)).isEqualByComparingTo("-0.01");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountB)).isZero();
        assertThat(db.scalar("SELECT COUNT(*) FROM cash_entries WHERE trading_account_id=? AND source_type='MONEY_TAIL'",accountB)).isEqualByComparingTo("1");
        long payout=Db.id(db.one("SELECT id FROM payouts WHERE trading_account_id=? AND source_type='MONEY_TAIL'",accountA).get("id"));
        assertThat(trades.payOne(payout)).isTrue();assertThat(trades.payOne(payout)).isFalse();
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",accountA)).isEqualByComparingTo("0.01");
    }
}
