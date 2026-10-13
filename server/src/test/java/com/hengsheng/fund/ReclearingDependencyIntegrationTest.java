package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

class ReclearingDependencyIntegrationTest extends ReclearingIntegrationTest {
    @Autowired MoneyIncomeService money;
    @Autowired ClearingService clearing;
    void moneyPurchase(Map<String,Object> a){long original=fund;fund=target;db.update("UPDATE fund_classes SET type='MONEY' WHERE id=?",fund);buy(a);fund=original;}
    void exit(Map<String,Object> a){long original=fund;fund=target;redeem(a,"1000.0000");fund=original;}
    void income(String rate){db.update("INSERT INTO money_income_records(fund_class_id,date,income_per_ten_thousand) VALUES(?,'2026-10-14',?)",target,rate);clock("2026-10-15T00:10:00");assertThat(money.run(LocalDate.of(2026,10,14)).get("failed")).isEqualTo(0);}
    @Test @Transactional void moneyPurchaseWithoutMarketRowSurvivesOtherFundRevision() throws Exception {
        var a=card();buy(a);moneyPurchase(a);db.update("DELETE FROM nav_records WHERE fund_class_id=?",target);
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"money-fixed"));
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",a.get("tid"),target)).isEqualByComparingTo("1000");
    }
    @Test @Transactional void pendingIncomePreservesFreezeAndWaitsForOriginalRedemption() throws Exception {
        var a=card();buy(a);moneyPurchase(a);clock("2026-10-14T14:00:00");trades.submit(user,Map.of("type","REDEMPTION","fundClassId",target,"tradingAccountId",a.get("tid"),"shares","1000.0000","requestKey","freeze"));income("1");
        assertThat(db.one("SELECT status FROM money_income_snapshots WHERE trading_account_id=?",a.get("tid")).get("status")).isEqualTo("PENDING");
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"pending-income"));
        assertThat(db.scalar("SELECT SUM(frozen_shares) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",a.get("tid"),target)).isEqualByComparingTo("1000");
        assertThat(db.scalar("SELECT applied_shares FROM money_income_snapshots WHERE trading_account_id=?",a.get("tid"))).isZero();
    }
    @Test @Transactional void negativeExitCashKeepsOriginalEffectiveDayAndBalancesAcrossRounds() throws Exception {
        var a=card();buy(a);moneyPurchase(a);exit(a);income("-1.23456");
        var before=db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid"));var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();long run=execute(p,"negative-income1");apply(run);
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo(before);
        assertThat(db.scalar("SELECT SUM(available_delta) FROM cash_entries WHERE trading_account_id=? AND source_type='MONEY_EXIT_INCOME'",a.get("tid"))).isEqualByComparingTo("-0.12");
        assertThat(db.scalar("SELECT COUNT(*) FROM cash_entries WHERE trading_account_id=? AND source_type='MONEY_EXIT_INCOME' AND effective_date='2026-10-14'",a.get("tid"))).isEqualByComparingTo("3");
        db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);var p2=preview(revision("2"));assertThat((List<?>)p2.get("blockers")).isEmpty();apply(execute(p2,"negative-income2"));
        assertThat(db.scalar("SELECT SUM(available_delta) FROM cash_entries WHERE trading_account_id=? AND source_type='MONEY_EXIT_INCOME'",a.get("tid"))).isEqualByComparingTo("-0.12");
        assertThat(db.scalar("SELECT tail FROM money_income_snapshots WHERE trading_account_id=?",a.get("tid"))).isZero();
    }
    @Test @Transactional void paidExitCashReplacementKeepsPaidStateAndAuditPointsAtReplacement() throws Exception {
        var a=card();buy(a);moneyPurchase(a);exit(a);income("1.23456");clock("2026-10-15T10:00:00");long pid=Db.id(db.one("SELECT payout_id FROM money_income_snapshots WHERE trading_account_id=?",a.get("tid")).get("payoutId"));assertThat(trades.payOne(pid)).isTrue();
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();long run=execute(p,"paid-income");apply(run);
        var after=db.one("SELECT * FROM payouts WHERE previous_payout_id=?",pid);assertThat(after.get("status")).isEqualTo("PAID");assertThat(trades.payOne(Db.id(after.get("id")))).isFalse();
        assertThat(db.scalar("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_result,'$.payoutId')) FROM reclearing_items WHERE run_id=? AND source_type='MONEY_INCOME'",run)).isEqualByComparingTo(after.get("id").toString());
    }
    @Test @Transactional void queueRejectionDoesNotPermanentlyBindReadyPreview() throws Exception {
        var a=card();buy(a);var p=preview(revision("1.25"));
        for(String type:List.of("MARKET","CONFIRM","PAY","MONEY"))clearing.submit(type,LocalDate.of(2026,10,13),Map.of(),user,type);
        long rejected=execute(p,"rejected");assertThat(db.one("SELECT state FROM clearing_runs WHERE id=?",rejected).get("state")).isEqualTo("REJECTED_QUEUE_FULL");
        assertThat(db.one("SELECT state FROM reclearing_previews WHERE id=?",p.get("previewId")).get("state")).isEqualTo("READY");
        db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE state='QUEUED'");assertThat(execute(p,"rejected")).isEqualTo(rejected);long run=execute(p,"retry");assertThat(run).isNotEqualTo(rejected);apply(run);
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("800");
    }
    Map<String,Object> twoLotConversion(Map<String,Object> a,String shares){
        buy(a);clock("2026-10-13T14:00:00");
        long second=Db.id(trades.submit(user,Map.of("type","PURCHASE","fundClassId",fund,"tradingAccountId",a.get("tid"),"bankCardId",a.get("id"),"bindingVersion",1,"amount","1000.00","requestKey","second-lot")).get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-13',2,TRUE)",fund);clock("2026-10-14T09:00:00");assertThat(trades.confirmOne(second)).isTrue();
        db.update("UPDATE fund_classes SET purchase_rate=0,redemption_rate=0 WHERE id IN(?,?)",fund,target);
        db.update("INSERT INTO conversion_pairs(source_class_id,target_class_id,enabled) VALUES(?,?,TRUE)",fund,target);
        clock("2026-10-14T14:00:00");var conversion=conversions.submit(user,Map.of("tradingAccountId",a.get("tid"),"sourceClassId",fund,"targetClassId",target,"shares",shares,"requestKey","two-lot-conversion"));
        for(long fid:List.of(fund,target))db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-14',1,TRUE)",fid);
        clock("2026-10-15T09:00:00");assertThat(conversions.run(LocalDate.of(2026,10,15)).get("processed")).isEqualTo(1);
        return conversion;
    }
    @Test @Transactional void sameCardConversionKeepsTargetLotsAndLaterFifoCost() throws Exception {
        var a=card();var conversion=twoLotConversion(a,"1500.0000");
        var targets=db.rows("SELECT id,shares,cost FROM position_lots WHERE trading_account_id=? AND source_type='CONVERSION' ORDER BY id",a.get("tid"));assertThat(targets).hasSize(2);
        String shares=targets.getFirst().get("shares").toString();clock("2026-10-15T14:00:00");
        long redemption=Db.id(trades.submit(user,Map.of("type","REDEMPTION","fundClassId",target,"tradingAccountId",a.get("tid"),"shares",shares,"requestKey","fifo-after-conversion")).get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-15',1,TRUE)",target);clock("2026-10-16T09:00:00");assertThat(trades.confirmOne(redemption)).isTrue();
        var before=db.rows("SELECT id,shares,cost FROM position_lots WHERE trading_account_id=? AND source_type='CONVERSION' ORDER BY id",a.get("tid"));
        var p=preview(revision("1.000000"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"preserve-target-fifo"));
        assertThat(db.rows("SELECT id,shares,cost FROM position_lots WHERE trading_account_id=? AND source_type='CONVERSION' ORDER BY id",a.get("tid"))).isEqualTo(before);
        assertThat(db.scalar("SELECT COUNT(*) FROM conversion_allocations WHERE conversion_id=?",conversion.get("id"))).isEqualByComparingTo("2");
    }
    @Test @Transactional void newConversionTargetLotKeepsDividendReferencesExecutable() throws Exception {
        var a=card();twoLotConversion(a,"750.0000");var dividends=context.getBean(DividendService.class);
        clock("2026-10-15T14:00:00");long event=Db.id(dividends.create(Map.of("fundClassId",target,"recordDate","2026-10-16","exDate","2026-10-19","paymentDate","2026-10-20","perShare","0.1")).get("id"));
        clock("2026-10-17T10:00:00");dividends.snapshot(event);clock("2026-10-19T09:00:00");
        var p=preview(revision("2.000000"));assertThat((List<?>)p.get("blockers")).isEmpty();long run=execute(p,"new-target-dividend");apply(run);
        assertThat(db.scalar("SELECT COUNT(*) FROM position_lots WHERE trading_account_id=? AND source_type='CONVERSION'",a.get("tid"))).isEqualByComparingTo("2");
        assertThat(db.scalar("SELECT COUNT(*) FROM dividend_lot_entitlements d LEFT JOIN position_lots l ON l.id=d.lot_id WHERE d.entitlement_id IN(SELECT id FROM dividend_entitlements WHERE event_id=?) AND (d.lot_id<0 OR l.id IS NULL)",event)).isZero();
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-19',1,TRUE)",target);clock("2026-10-20T10:00:00");
        assertThat(dividends.execute(event).get("failed")).isEqualTo(0);
        long payout=Db.id(db.one("SELECT payout_id FROM dividend_entitlements WHERE event_id=?",event).get("payoutId"));assertThat(trades.payOne(payout)).isTrue();
    }
    @Test @Transactional void completedPreviewStillEnforcesPermanentRequestKeyAliases() throws Exception {
        var a=card();buy(a);var first=preview(revision("1.25"));long run=execute(first,"permanent-a");apply(run);db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);
        var second=preview(revision("2"));long other=execute(second,"permanent-b");apply(other);
        assertThatThrownBy(()->execute(first,"permanent-b")).isInstanceOf(ApiException.class).hasMessageContaining("请求键");
        assertThat(execute(first,"permanent-alias")).isEqualTo(run);
        assertThatThrownBy(()->execute(second,"permanent-alias")).isInstanceOf(ApiException.class).hasMessageContaining("请求键");
    }
}
