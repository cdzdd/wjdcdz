package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

class ReclearingRightsIntegrationTest extends ReclearingIntegrationTest {
    @Autowired DividendService dividends;
    @Autowired MoneyIncomeService money;
    @Autowired OfferingService offerings;
    Map<String,Object> displayedDividend(long eid){
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();var prior=security.getAuthentication();
        security.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(db.one("SELECT username FROM users WHERE id=?",user).get("username"),null,List.of()));
        try{return context.getBean(AdvancedController.class).dividends().stream().filter(row->Db.id(row.get("id"))==eid).findFirst().orElseThrow();}finally{security.setAuthentication(prior);}
    }
    long dividend(Map<String,Object> a,String mode,boolean execute) {
        clock("2026-10-13T14:00:00");
        dividends.preference(user,fund,mode);
        long eid=Db.id(dividends.create(Map.of("fundClassId",fund,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-14","perShare","0.1")).get("id"));
        clock("2026-10-14T10:00:00");dividends.snapshot(eid);
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-14',1,TRUE)",fund);
        if(execute)dividends.execute(eid);
        return Db.id(db.one("SELECT id FROM dividend_entitlements WHERE event_id=?",eid).get("id"));
    }
    @Test @Transactional void registeredCashRightsRecomputeWithoutPrematureCashOrCost() throws Exception {
        var a=card();buy(a);long eid=dividend(a,"CASH",false);var p=preview(revision("1.25"));
        assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"rights-pending"));
        assertThat(db.scalar("SELECT amount FROM dividend_entitlements WHERE id=?",eid)).isEqualByComparingTo("80");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("99000");
        assertThat(db.scalar("SELECT SUM(cost) FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("1000");
    }
    @Test @Transactional void paidDividendUsesNetCashAndRebuildsHistoricalCost() throws Exception {
        var a=card();buy(a);long eid=dividend(a,"CASH",true);long payout=Db.id(db.one("SELECT payout_id FROM dividend_entitlements WHERE id=?",eid).get("payoutId"));assertThat(trades.payOne(payout)).isTrue();
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"rights-paid"));
        assertThat(db.scalar("SELECT amount FROM dividend_entitlements WHERE id=?",eid)).isEqualByComparingTo("80");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("99080");
        assertThat(db.scalar("SELECT SUM(cost) FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("920");
        assertThat(db.scalar("SELECT SUM(cost_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("920");
        assertThat(trades.payOne(Db.id(db.one("SELECT payout_id FROM dividend_entitlements WHERE id=?",eid).get("payoutId")))).isFalse();
    }
    @Test @Transactional void reinvestmentPreservesSourceAndCanBeReplayedTwice() throws Exception {
        var a=card();buy(a);long eid=dividend(a,"REINVEST",true);var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();long run=execute(p,"reinvest1");apply(run);
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE source_type='DIVIDEND' AND source_id=?",String.valueOf(eid))).isEqualByComparingTo("80");
        assertThat(Db.decimal(displayedDividend(eid).get("reinvestShares"))).isEqualByComparingTo("80");
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("880");
        db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);
        var p2=preview(revision("2"));assertThat((List<?>)p2.get("blockers")).isEmpty();apply(execute(p2,"reinvest2"));
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("550");
        assertThat(Db.decimal(displayedDividend(eid).get("reinvestShares"))).isEqualByComparingTo("50");
        assertThat(db.scalar("SELECT SUM(cost_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("1000");
    }
    @Test @Transactional void establishedOfferingIsFixedSeedForOtherMarketRevisions() throws Exception {
        var a=card();buy(a);db.update("UPDATE fund_classes SET state='RAISING' WHERE id=?",target);
        clock("2026-10-14T14:00:00");var o=offerings.subscribe(user,Map.of("fundClassId",target,"tradingAccountId",a.get("tid"),"bankCardId",a.get("id"),"bindingVersion",1,"amount","1000.00","requestKey","offer"));
        offerings.finalizeOffering(target,Map.of("outcome","ESTABLISHED","endDate","2026-10-14","initialNav","2","interestRate","0"));
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"offering"));
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE source_type='OFFERING' AND source_id=?",o.get("id").toString())).isEqualByComparingTo("500");
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("1300");
    }
    @Test @Transactional void dailyIncomeRecomputesQualifiedSharesAndCarriesTailAcrossDays() throws Exception {
        var a=card();buy(a);long equity=fund;fund=target;db.update("UPDATE fund_classes SET type='MONEY' WHERE id=?",fund);buy(a);
        db.update("INSERT INTO money_income_records(fund_class_id,date,income_per_ten_thousand) VALUES(?,'2026-10-14',1.23456789),(?,'2026-10-15',-0.23456789)",fund,fund);
        clock("2026-10-16T00:10:00");assertThat(money.run(LocalDate.of(2026,10,15)).get("failed")).isEqualTo(0);
        fund=equity;var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"income"));
        assertThat(db.scalar("SELECT shares FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-14'",a.get("tid"))).isEqualByComparingTo("1000");
        assertThat(db.scalar("SELECT applied_shares FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-14'",a.get("tid"))).isEqualByComparingTo("0.1234");
        assertThat(db.scalar("SELECT shares FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-15'",a.get("tid"))).isEqualByComparingTo("1000.1234");
        assertThat(db.scalar("SELECT tail FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-15'",a.get("tid"))).isEqualByComparingTo("-0.000002894568");
        assertThat(Db.decimal(money.eligible(target,LocalDate.of(2026,10,15)).stream().filter(x->Db.id(x.get("tradingAccountId"))==Db.id(a.get("tid"))).findFirst().orElseThrow().get("eligibleShares"))).isEqualByComparingTo("1000.1234");
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=?",a.get("tid")));
    }
    @Test @Transactional void exitedIncomeCashIsReplayedWithoutRecreatingHolding() throws Exception {
        var a=card();buy(a);long equity=fund;fund=target;db.update("UPDATE fund_classes SET type='MONEY' WHERE id=?",fund);buy(a);redeem(a,"1000.0000");
        db.update("INSERT INTO money_income_records(fund_class_id,date,income_per_ten_thousand) VALUES(?,'2026-10-14',1.23456)",fund);
        clock("2026-10-15T00:10:00");money.run(LocalDate.of(2026,10,14));
        fund=equity;var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"new-exit"));
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",a.get("tid"),target)).isZero();
        assertThat(db.scalar("SELECT cash_adjustment FROM money_income_snapshots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("0.12");
        assertThat(db.scalar("SELECT amount FROM payouts WHERE source_type='MONEY_EXIT_INCOME' AND trading_account_id=? AND status='PENDING'",a.get("tid"))).isEqualByComparingTo("0.12");
        assertThat(db.scalar("SELECT original_tail FROM money_exit_disposals WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("0.003456");
    }}
