package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class CoreRulesTest {
    @Autowired Db db;@Autowired FundService funds;@Autowired AccountService accounts;@Autowired TradeService trades;
    @Autowired PortfolioService portfolio;
    @Autowired CoreController core;
    @Autowired CardChangeService changes;
    @Autowired DividendService dividends;
    @Autowired OfferingService offerings;
    long uid,tid,cid,fid;String username;
    @BeforeEach void fixture(){
        username="rules"+UUID.randomUUID().toString().substring(0,12);uid=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','规则测试',TRUE,5,'2027-10-12')",username);
        cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,holder_name,sim_number,request_key) VALUES(?,'模拟银行','规则测试',?,'fixture')",uid,"SIMTEST"+UUID.randomUUID());
        tid=db.insert("INSERT INTO trading_accounts(user_id,bank_card_id,account_no) VALUES(?,?,?)",uid,cid,"TEST"+UUID.randomUUID());db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",tid);
        accounts.credit(tid,"INITIAL","fixture",new BigDecimal("1000.00"));db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",tid,cid);
        fid=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,purchase_rate,redemption_rate) VALUES(?,'规则基金','STOCK','模拟管理',1,3,0.015,0.005)","F"+UUID.randomUUID().toString().substring(0,12));
        db.update("UPDATE app_settings SET value='2026-10-12T14:00:00' WHERE name='demo_clock'");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username,null,List.of()));
    }
    Map<String,Object> buy(String key,String amount){return new HashMap<>(Map.of("type","PURCHASE","requestKey",key,"fundClassId",fid,"tradingAccountId",tid,"bankCardId",cid,"bindingVersion",1,"amount",amount));}
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
    @Test void cutoffAndClosedDatesUseExplicitCalendar(){
        assertThat(funds.applicationDate(fid,LocalDateTime.parse("2026-10-12T14:59:59"))).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(funds.applicationDate(fid,LocalDateTime.parse("2026-10-12T15:00:00"))).isEqualTo(LocalDate.parse("2026-10-13"));
        assertThat(funds.applicationDate(fid,LocalDateTime.parse("2026-10-16T15:01:00"))).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(funds.applicationDate(fid,LocalDateTime.parse("2026-10-17T10:00:00"))).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThatThrownBy(()->funds.nextOpen(LocalDate.parse("2030-01-01"))).isInstanceOf(ApiException.class).hasMessageContaining("缺少交易日历");
    }
    @Test void movingDemoClockBackDoesNotUseStoredFutureNavForCurrentValuation(){
        db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,'2026-10-09',1.2),(?,'2026-10-13',9.9)",fid,fid);
        long fa=accounts.linkFundAccount(uid,tid,fid);trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("100.0000"),new BigDecimal("100.00"),LocalDate.parse("2026-10-01"),"FIXTURE","shares");
        assertThat(funds.fund(fid).get("nav")).isEqualTo("1.200000");
        assertThat(portfolio.positions(uid).getFirst().get("marketValue")).isEqualTo("120.00");
    }
    @Test void normalProductEditingCannotBypassTerminationOrReviveTerminalProduct(){
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("state","TERMINATED"))).isInstanceOf(ApiException.class).hasMessageContaining("专用");
        db.update("UPDATE fund_classes SET state='TERMINATED' WHERE id=?",fid);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("state","OPEN"))).isInstanceOf(ApiException.class).hasMessageContaining("终态");
        db.update("UPDATE fund_classes SET state='LIQUIDATED' WHERE id=?",fid);
        assertThat(funds.saveFund(fid,Map.of("name","已清盘基金资料更正")).get("state")).isEqualTo("LIQUIDATED");
    }
    @Test void delayedConfirmationStartsHoldingEligibilityOnlyOnActualConfirmationDate(){
        var order=trades.submit(uid,buy("late","600.00"));long oid=Db.id(order.get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,'2026-10-12',1.2)",fid);db.update("UPDATE app_settings SET value='2026-10-19T10:00:00' WHERE name='demo_clock'");
        assertThat(trades.confirmOne(oid)).isTrue();var confirmed=trades.order(uid,oid);
        assertThat(confirmed.get("confirmationDate")).isEqualTo("2026-10-19");assertThat(confirmed.get("plannedConfirmationDate")).isEqualTo("2026-10-13");assertThat(confirmed.get("confirmedAt").toString()).isEqualTo("2026-10-19T10:00");
        assertThat(db.one("SELECT acquired_date FROM position_lots WHERE source_type='PURCHASE' AND source_id=?",String.valueOf(oid)).get("acquiredDate")).isEqualTo("2026-10-19");
    }
    @Test void fixedFeeTierMustLeavePositivePrincipalAtItsMinimumApplicableAmount(){
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->core.feeTiers(fid,Map.of("tiers",List.of(Map.of("minAmount","1000.00","rate","0","fixedFee","1000.00"))))).isInstanceOf(ApiException.class);
    }
    @Test void duplicateSubmissionDoesNotReserveCashTwiceAndDifferentRequestConflicts(){
        var in=buy("once","700.00");var first=trades.submit(uid,in);var again=trades.submit(uid,in);assertThat(again.get("id")).isEqualTo(first.get("id"));
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",tid)).isEqualByComparingTo("300.00");assertThat(db.scalar("SELECT frozen FROM wallets WHERE trading_account_id=?",tid)).isEqualByComparingTo("700.00");
        in.put("amount","701.00");assertThatThrownBy(()->trades.submit(uid,in)).isInstanceOf(ApiException.class).hasMessageContaining("请求键");
    }
    @Test void insufficientCashDoesNotBorrowOtherAccountAndTaAccountIsReused(){
        long second=db.insert("INSERT INTO trading_accounts(user_id,bank_card_id,account_no) VALUES(?,?,?)",uid,cid,"TWO"+UUID.randomUUID());db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",second);accounts.credit(second,"TOPUP","large",new BigDecimal("100000.00"));
        assertThatThrownBy(()->trades.submit(uid,buy("large","1100.00"))).isInstanceOf(ApiException.class).hasMessageContaining("余额不足");
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",second)).isEqualByComparingTo("100000.00");
        assertThat(accounts.linkFundAccount(uid,tid,fid)).isEqualTo(accounts.linkFundAccount(uid,second,fid));
    }
    @Test void feeTierIsSnapshotAndCannotRepriceExistingOrder(){
        db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,'2026-10-09',1.2)",fid);
        db.update("INSERT INTO fee_tiers(fund_class_id,min_amount,rate) VALUES(?,0,0.015),(?,500,0.010)",fid,fid);
        var estimate=trades.quote(uid,Map.of("type","PURCHASE","fundClassId",fid,"tradingAccountId",tid,"amount","600.00"));
        assertThat(estimate.get("fee")).isEqualTo("5.94");
        var order=trades.submit(uid,buy("tier","600.00"));assertThat(order.get("purchaseRate")).isEqualTo("0.01000000");
        db.update("UPDATE fee_tiers SET rate=0.02 WHERE fund_class_id=?",fid);assertThat(trades.order(uid,Db.id(order.get("id"))).get("purchaseRate")).isEqualTo("0.01000000");
    }
    @Test void fifoReservationAndConsumptionPreserveBatchCostsAndSources(){
        long fa=accounts.linkFundAccount(uid,tid,fid);
        long newer=trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("200.0000"),new BigDecimal("300.00"),LocalDate.parse("2026-10-09"),"FIXTURE","new");
        long older=trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("200.0000"),new BigDecimal("200.00"),LocalDate.parse("2026-10-01"),"FIXTURE","old");
        var allocation=trades.reserveLots(uid,tid,fid,new BigDecimal("250.0000"));assertThat(allocation).hasSize(2);assertThat(Db.id(allocation.get(0).get("lotId"))).isEqualTo(older);assertThat(allocation.get(1).get("shares")).isEqualTo("50.0000");assertThat(allocation.get(1).get("cost")).isEqualTo("75.00");
        trades.consumeLots(allocation,"REDEMPTION","consume");trades.consumeLots(allocation,"REDEMPTION","consume");
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE id=?",older)).isEqualByComparingTo("0");assertThat(db.scalar("SELECT shares FROM position_lots WHERE id=?",newer)).isEqualByComparingTo("150.0000");assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",newer)).isEqualByComparingTo("225.00");
    }
    @Test void redemptionUsesDividendAdjustedCostWhenReservationPredatesExRights(){
        long fa=accounts.linkFundAccount(uid,tid,fid);
        long lot=trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("1000.0000"),new BigDecimal("1200.00"),LocalDate.parse("2026-10-01"),"FIXTURE","dividend-lot");
        db.update("UPDATE app_settings SET value='2026-10-13T14:00:00' WHERE name='demo_clock'");
        var event=dividends.create(Map.of("fundClassId",fid,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-16","perShare","0.100000"));
        db.update("UPDATE app_settings SET value='2026-10-14T14:00:00' WHERE name='demo_clock'");dividends.snapshot(Db.id(event.get("id")));
        var order=trades.submit(uid,Map.of("type","REDEMPTION","fundClassId",fid,"tradingAccountId",tid,"shares","500.0000","requestKey","after-ex"));long oid=Db.id(order.get("id"));
        assertThat(db.scalar("SELECT cost FROM order_lot_allocations WHERE order_id=?",oid)).isEqualByComparingTo("600.00");
        db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,'2026-10-14',1.1)",fid);dividends.execute(Db.id(event.get("id")));
        assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",lot)).isEqualByComparingTo("1100.00");
        db.update("UPDATE app_settings SET value='2026-10-15T09:00:00' WHERE name='demo_clock'");assertThat(trades.confirmOne(oid)).isTrue();
        assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",lot)).isEqualByComparingTo("550.00");
        assertThat(db.scalar("SELECT cost FROM order_lot_allocations WHERE order_id=?",oid)).isEqualByComparingTo("550.00");
        assertThat(db.scalar("SELECT SUM(cost_delta) FROM share_entries WHERE lot_id=?",lot)).isEqualByComparingTo("550.00");
    }
    @Test void existingBusinessFreezesProductTypeRegistrarAndManagerButAllowsOtherDetails(){
        trades.submit(uid,buy("identity","100.00"));db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("type","MONEY"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("registrarId",2))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("manager","另一管理人"))).isInstanceOf(ApiException.class);
        assertThat(funds.saveFund(fid,Map.of("name","可更正的基金说明")).get("name")).isEqualTo("可更正的基金说明");
    }
    @Test void aProductWithNoBusinessCanStillCorrectItsIdentity(){
        db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);var saved=funds.saveFund(fid,Map.of("type","MONEY","registrarId",2,"manager","更正模拟管理"));
        assertThat(saved.get("type")).isEqualTo("MONEY");assertThat(Db.id(saved.get("registrarId"))).isEqualTo(2);assertThat(saved.get("manager")).isEqualTo("更正模拟管理");
    }
    @Test void ordinaryEditingCannotReopenAProductWithAnUnsettledOffering(){
        db.update("UPDATE fund_classes SET state='RAISING' WHERE id=?",fid);
        offerings.subscribe(uid,Map.of("fundClassId",fid,"tradingAccountId",tid,"bankCardId",cid,"bindingVersion",1,"amount","100.00","requestKey","open-offering"));db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("state","OPEN"))).isInstanceOf(ApiException.class);
        assertThat(db.one("SELECT state FROM fund_classes WHERE id=?",fid).get("state")).isEqualTo("RAISING");
    }
    @Test void ordinaryEditingCannotSendAHeldOpenProductBackToRaising(){
        long fa=accounts.linkFundAccount(uid,tid,fid);trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("10.0000"),new BigDecimal("10.00"),LocalDate.parse("2026-10-01"),"FIXTURE","held");db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->funds.saveFund(fid,Map.of("state","RAISING"))).isInstanceOf(ApiException.class);
        assertThat(db.one("SELECT state FROM fund_classes WHERE id=?",fid).get("state")).isEqualTo("OPEN");
    }
    @Test void terminatedFundPublishesMissingAcceptedApplicationNavOnlyThroughTerminationDate(){
        var order=trades.submit(uid,buy("before-termination","100.00"));db.update("UPDATE fund_classes SET state='TERMINATED',terminated_at='2026-10-12T16:00:00' WHERE id=?",fid);
        db.update("UPDATE app_settings SET value='2026-10-15T16:00:00' WHERE name='demo_clock'");funds.market(LocalDate.parse("2026-10-15"));
        assertThat(db.scalar("SELECT COUNT(*) FROM nav_records WHERE fund_class_id=? AND date='2026-10-12'",fid)).isEqualByComparingTo("1");
        assertThat(db.scalar("SELECT COUNT(*) FROM nav_records WHERE fund_class_id=? AND date>'2026-10-12'",fid)).isEqualByComparingTo("0");
        assertThat(trades.confirmOne(Db.id(order.get("id")))).isTrue();
    }
    @Test void syntheticPricesRetainCumulativeDividendAdjustmentAfterTheExDate(){
        db.update("UPDATE fund_classes SET code='DIVTEST' WHERE id=?",fid);
        dividends.create(Map.of("fundClassId",fid,"recordDate","2026-10-12","exDate","2026-10-13","paymentDate","2026-10-14","perShare","0.100000"));
        db.update("UPDATE app_settings SET value='2026-10-14T16:00:00' WHERE name='demo_clock'");funds.market(LocalDate.parse("2026-10-14"));
        // Fixed fixture baselines were independently computed: 1.182300 on the 13th and 1.182400 on the 14th.
        assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-13'",fid)).isEqualByComparingTo("1.082300");
        assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-14'",fid)).isEqualByComparingTo("1.082400");
    }
    @Test void cumulativeDividendAdjustmentCannotPublishNonPositiveSyntheticNav(){
        db.update("UPDATE fund_classes SET code='DIVTEST' WHERE id=?",fid);
        dividends.create(Map.of("fundClassId",fid,"recordDate","2026-10-12","exDate","2026-10-13","paymentDate","2026-10-14","perShare","0.600000"));
        dividends.create(Map.of("fundClassId",fid,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-15","perShare","0.600000"));
        db.update("UPDATE app_settings SET value='2026-10-14T16:00:00' WHERE name='demo_clock'");
        assertThatThrownBy(()->funds.market(LocalDate.parse("2026-10-14"))).isInstanceOf(ApiException.class).hasMessageContaining("净值非正");
    }
    @Test void consumptionRefreshesConversionCostAndPreservesFinalCentRemainder(){
        long fa=accounts.linkFundAccount(uid,tid,fid),lot=trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("3.0000"),new BigDecimal("1.00"),LocalDate.parse("2026-10-01"),"FIXTURE","three");
        var first=trades.reserveLots(uid,tid,fid,new BigDecimal("1.0000"));var second=trades.reserveLots(uid,tid,fid,new BigDecimal("1.0000"));
        trades.consumeLots(first,"CONVERSION","first");trades.consumeLots(second,"CONVERSION","second");
        assertThat(second.getFirst().get("cost")).isEqualTo("0.34");assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",lot)).isEqualByComparingTo("0.33");
        var last=trades.reserveLots(uid,tid,fid,new BigDecimal("1.0000"));trades.consumeLots(last,"CONVERSION","last");trades.consumeLots(last,"CONVERSION","last");
        assertThat(last.getFirst().get("cost")).isEqualTo("0.33");assertThat(db.scalar("SELECT shares FROM position_lots WHERE id=?",lot)).isEqualByComparingTo("0");assertThat(db.scalar("SELECT cost FROM position_lots WHERE id=?",lot)).isEqualByComparingTo("0");
        assertThat(db.scalar("SELECT SUM(cost_delta) FROM share_entries WHERE lot_id=?",lot)).isEqualByComparingTo("0");
    }
    Map<String,Object> pendingCardChange(){accounts.cashOperation(tid,Map.of("amount","1000.00","requestKey","empty"),false);return changes.request(Map.of("tradingAccountId",tid,"bankName","模拟银行","holderName","规则测试","reason","模拟更换结算卡","requestKey","change"));}
    @Test void pendingCardChangePreventsUnbindingEvenWhenAccountHasNoAssets(){
        pendingCardChange();assertThatThrownBy(()->accounts.unbind(cid,Map.of())).isInstanceOf(ApiException.class);
        assertThat(db.one("SELECT status FROM trading_accounts WHERE id=?",tid).get("status")).isEqualTo("ACTIVE");
    }
    @Test void pendingCardChangeCannotApproveAnAlreadyClosedAccount(){
        var change=pendingCardChange();db.update("UPDATE trading_accounts SET status='CLOSED' WHERE id=?",tid);db.update("UPDATE users SET role='ADMIN' WHERE id=?",uid);
        assertThatThrownBy(()->changes.approve(Db.id(change.get("id")),Map.of("reason","模拟核验通过"))).isInstanceOf(ApiException.class);
        assertThat(db.one("SELECT status FROM bank_cards WHERE id=?",change.get("newCardId")).get("status")).isEqualTo("DISABLED");
    }
    @Test void redemptionFeesFollowEachLotsHoldingPeriod(){
        long fa=accounts.linkFundAccount(uid,tid,fid);trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("200.0000"),new BigDecimal("200.00"),LocalDate.parse("2026-10-01"),"FIXTURE","old");trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("200.0000"),new BigDecimal("200.00"),LocalDate.parse("2026-10-09"),"FIXTURE","new");
        db.update("INSERT INTO nav_records(fund_class_id,date,nav) VALUES(?,'2026-10-09',1.2)",fid);
        var quote=trades.quote(uid,Map.of("type","REDEMPTION","fundClassId",fid,"tradingAccountId",tid,"shares","250.0000"));assertThat(quote.get("fee")).isEqualTo("2.10");assertThat(quote.get("netAmount")).isEqualTo("297.90");
    }
    @Test void csvFormulaPrefixesAreEscapedButNegativeNumericAmountsStayNumeric(){
        assertThat(CoreController.csvCell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(CoreController.csvCell("-1.25")).isEqualTo("\"-1.25\"");assertThat(CoreController.csvCell("@cmd")).isEqualTo("\"'@cmd\"");
    }
    @Test void redemptionGroupRejectsInsufficientSharesInItsSpecificAccount(){
        long second=db.insert("INSERT INTO trading_accounts(user_id,bank_card_id,account_no) VALUES(?,?,?)",uid,cid,"GROUP"+UUID.randomUUID());db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",second);
        long fa=accounts.linkFundAccount(uid,tid,fid),fb=accounts.linkFundAccount(uid,second,fid);
        trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("500.0000"),new BigDecimal("500.00"),LocalDate.parse("2026-10-01"),"FIXTURE","first");trades.addLot(uid,second,fid,cid,fb,new BigDecimal("300.0000"),new BigDecimal("300.00"),LocalDate.parse("2026-10-01"),"FIXTURE","second");
        var invalid=Map.<String,Object>of("fundClassId",fid,"requestKey","invalid-group","allocations",List.of(Map.of("tradingAccountId",tid,"shares","100.0000"),Map.of("tradingAccountId",second,"shares","400.0000")));
        assertThatThrownBy(()->trades.group(uid,invalid)).isInstanceOf(ApiException.class).hasMessageContaining("份额不足");
    }
}
