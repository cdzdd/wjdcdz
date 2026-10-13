package com.hengsheng.fund;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class ReclearingIntegrationTest {
    @Autowired Db db;
    @Autowired TradeService trades;
    @Autowired ConversionService conversions;
    @Autowired ApplicationContext context;
    @Autowired PlatformTransactionManager manager;
    long user,fund,target;
    @org.springframework.test.context.transaction.BeforeTransaction void prepareAdmissionMetadata(){
        // Prepare the known fixture days before its outer rollback transaction; concurrency tests use a different, missing day.
        for(String date:List.of("2026-10-12","2026-10-13","2026-10-14","2026-10-15","2026-10-16","2026-10-19"))db.update("INSERT IGNORE INTO application_days(business_date,state) VALUES(?,'OPEN')",date);
    }
    Map<String,Object> card() {
        String key=UUID.randomUUID().toString();
        if(user==0){user=db.insert("INSERT INTO users(username,password_hash,display_name,role,opened,risk_level,risk_expires_at) VALUES(?,'unused','重清测试','ADMIN',TRUE,5,'2027-12-31')","reclear"+key);fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'重清测试','EQUITY','重清管理人',1,1)","RC"+key.substring(0,20));target=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'转换目标','EQUITY','重清管理人',1,1)","RT"+key.substring(0,20));}
        long cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'重清测试',?)",user,key,key);
        long tid=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,key,cid);
        db.update("INSERT INTO wallets(trading_account_id,available) VALUES(?,100000)",tid);
        db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,'INITIAL',?,'CREDIT',100000,0,100000,'2026-10-12 09:00:00')",user,tid,key);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",tid,cid);
        return Map.of("id",cid,"tid",tid);
    }
    void clock(String at){db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",at);}
    long buy(Map<String,Object> card) {
        clock("2026-10-12T14:00:00");db.update("UPDATE fund_classes SET purchase_rate=0 WHERE id=1");db.update("DELETE FROM fee_tiers WHERE fund_class_id="+fund+"");
        long id=Db.id(trades.submit(user,Map.of("type","PURCHASE","fundClassId",fund,"tradingAccountId",card.get("tid"),"bankCardId",card.get("id"),"bindingVersion",1,"amount","1000.00","requestKey",UUID.randomUUID().toString())).get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES("+fund+",'2026-10-12',1,TRUE) ON DUPLICATE KEY UPDATE nav=1,published=TRUE");clock("2026-10-13T09:00:00");assertThat(trades.confirmOne(id)).isTrue();return id;
    }
    Object invoke(String bean,String method,Class<?>[] types,Object...args) throws Exception {
        Object service=context.getBean(bean);
        try{return service.getClass().getMethod(method,types).invoke(service,args);}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    long revision(String nav) throws Exception {return (long)invoke("navRevisionService","createRevisionSet",new Class[]{List.class,String.class,String.class,long.class},List.of(Map.of("fundClassId",fund,"marketDate","2026-10-12","nav",nav)),UUID.randomUUID().toString(),"纠正已发布净值",user);}
    Map<String,Object> preview(long revision) throws Exception {return (Map<String,Object>)invoke("reclearingService","preview",new Class[]{long.class,long.class},revision,user);}
    long execute(Map<String,Object> preview,String key) throws Exception {return (long)invoke("reclearingService","execute",new Class[]{long.class,String.class,String.class,long.class},Db.id(preview.get("previewId")),preview.get("scopeVersion"),key,user);}
    void apply(long run) throws Exception {invoke("reclearingService","apply",new Class[]{long.class},run);}
    @Test @Transactional void twoCardPurchaseCanBeRecleared() throws Exception {
        var a=card();var b=card();long first=buy(a),second=buy(b);var original=trades.order(user,first);
        long revision=revision("1.25");assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id="+fund+" AND date='2026-10-12'")).isEqualByComparingTo("1");
        var p=preview(revision);assertThat((List<?>)p.get("blockers")).isEmpty();assertThat((List<?>)p.get("affectedAccounts")).hasSize(2);
        long run=execute(p,"once");apply(run);apply(run);
        for(long oid:List.of(first,second))assertThat(Db.decimal(trades.order(user,oid).get("shares"))).isEqualByComparingTo("800");
        assertThat(trades.order(user,first).get("applicationNo")).isEqualTo(original.get("applicationNo"));assertThat(trades.order(user,first).get("confirmationNo")).isNotEqualTo(original.get("confirmationNo"));
        assertThat(db.scalar("SELECT cost FROM position_lots WHERE source_type='PURCHASE' AND source_id=?",String.valueOf(first))).isEqualByComparingTo("1000");
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("800");
        assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",first)).isEqualByComparingTo("2");
    }
    @Test @Transactional void previewBecomesStaleAfterAssetWrite() throws Exception {
        var a=card();buy(a);var p=preview(revision("1.25"));db.update("UPDATE wallets SET available=available+1 WHERE trading_account_id=?",a.get("tid"));
        assertThatThrownBy(()->execute(p,"stale")).isInstanceOf(ApiException.class).hasMessageContaining("预演");
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("1000");
    }
    @Test @Transactional void newAffectedAccountInvalidatesMembership() throws Exception {
        var a=card();buy(a);var p=preview(revision("1.25"));buy(card());
        assertThatThrownBy(()->execute(p,"new-member")).isInstanceOf(ApiException.class).hasMessageContaining("预演");
    }
    @Test @Transactional void fixedLaterRedemptionCannotBorrowFutureShares() throws Exception {
        var a=card();buy(a);clock("2026-10-13T14:00:00");trades.submit(user,Map.of("type","REDEMPTION","fundClassId",fund,"tradingAccountId",a.get("tid"),"shares","900.0000","requestKey","redeem"));
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isNotEmpty();
        assertThat(db.scalar("SELECT shares FROM position_lots WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("1000");
    }
    long redeem(Map<String,Object> a,String shares) {
        clock("2026-10-13T14:00:00");long oid=Db.id(trades.submit(user,Map.of("type","REDEMPTION","fundClassId",fund,"tradingAccountId",a.get("tid"),"shares",shares,"requestKey",UUID.randomUUID().toString())).get("id"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES("+fund+",'2026-10-13',1.5,TRUE) ON DUPLICATE KEY UPDATE nav=1.5,published=TRUE");clock("2026-10-14T09:00:00");assertThat(trades.confirmOne(oid)).isTrue();return oid;
    }
    long reviseRedemption() throws Exception {return (long)invoke("navRevisionService","createRevisionSet",new Class[]{List.class,String.class,String.class,long.class},List.of(Map.of("fundClassId",fund,"marketDate","2026-10-13","nav","1.2")),UUID.randomUUID().toString(),"赎回价格修订",user);}
    @Test @Transactional void paidPayoutUsesOnlyNetDifferenceAndCannotPayAgain() throws Exception {
        var a=card();buy(a);long order=redeem(a,"500.0000");long payout=Db.id(db.one("SELECT id FROM payouts WHERE source_type='REDEMPTION' AND source_id=?",String.valueOf(order)).get("id"));clock("2026-10-20T10:00:00");assertThat(trades.payOne(payout)).isTrue();
        BigDecimal before=db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid")),oldAmount=db.scalar("SELECT amount FROM payouts WHERE id=?",payout);
        var p=preview(reviseRedemption());assertThat((List<?>)p.get("blockers")).isEmpty();long run=execute(p,"paid");apply(run);
        var replacement=db.one("SELECT * FROM payouts WHERE previous_payout_id=?",payout);assertThat(replacement.get("status")).isEqualTo("PAID");assertThat(trades.payOne(Db.id(replacement.get("id")))).isFalse();
        assertThat(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo(before.add(Db.decimal(replacement.get("amount"))).subtract(oldAmount));
        assertThat(db.one("SELECT status FROM payouts WHERE id=?",payout).get("status")).isEqualTo("REVERSED");
    }
    @Test @Transactional void paidPayoutAfterWithdrawalBlocksWholeScope() throws Exception {
        var a=card();buy(a);long order=redeem(a,"500.0000");long payout=Db.id(db.one("SELECT id FROM payouts WHERE source_type='REDEMPTION' AND source_id=?",String.valueOf(order)).get("id"));clock("2026-10-20T10:00:00");trades.payOne(payout);BigDecimal withdrawal=BigDecimal.ONE.subtract(db.scalar("SELECT available FROM wallets WHERE trading_account_id=?",a.get("tid")));db.update("UPDATE wallets SET available=1 WHERE trading_account_id=?",a.get("tid"));db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,'WITHDRAW','withdraw','WITHDRAW',?,0,?,'2026-10-20 10:00:00')",user,a.get("tid"),withdrawal,withdrawal);
        var p=preview(reviseRedemption());assertThat((List<?>)p.get("blockers")).isNotEmpty();assertThatThrownBy(()->execute(p,"insufficient")).isInstanceOf(ApiException.class);assertThat(trades.order(user,order).get("nav")).isEqualTo("1.500000");
    }
    @Test @Transactional void laterFifoRedemptionAndPendingFreezeUseRebuiltLots() throws Exception {
        var a=card();long purchase=buy(a);long redeemed=redeem(a,"500.0000");clock("2026-10-14T14:00:00");trades.submit(user,Map.of("type","REDEMPTION","fundClassId",fund,"tradingAccountId",a.get("tid"),"shares","200.0000","requestKey","pending"));
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"fifo"));
        var lot=db.one("SELECT * FROM position_lots WHERE source_type='PURCHASE' AND source_id=?",String.valueOf(purchase));assertThat(Db.decimal(lot.get("shares"))).isEqualByComparingTo("300");assertThat(Db.decimal(lot.get("frozenShares"))).isEqualByComparingTo("200");assertThat(Db.decimal(lot.get("cost"))).isEqualByComparingTo("375");
        assertThat(db.scalar("SELECT cost FROM order_lot_allocations WHERE order_id=?",redeemed)).isEqualByComparingTo("625");
    }
    @Test @Transactional void conversionAndItsTargetRemainInTheAccountClosure() throws Exception {
        var a=card();buy(a);clock("2026-10-13T14:00:00");db.update("INSERT INTO conversion_pairs(source_class_id,target_class_id,enabled) VALUES("+fund+","+target+",TRUE) ON DUPLICATE KEY UPDATE enabled=TRUE");
        var c=conversions.submit(user,Map.of("tradingAccountId",a.get("tid"),"sourceClassId",fund,"targetClassId",target,"shares","500.0000","requestKey","convert"));
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES("+fund+",'2026-10-13',1,TRUE),("+target+",'2026-10-13',2,TRUE) ON DUPLICATE KEY UPDATE nav=VALUES(nav),published=TRUE");clock("2026-10-14T09:00:00");conversions.run(java.time.LocalDate.of(2026,10,14));
        var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"conversion"));
        assertThat(db.scalar("SELECT SUM(cost) FROM position_lots WHERE trading_account_id=? AND fund_class_id="+target+"",a.get("tid"))).isEqualByComparingTo("625");assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='CONVERSION' AND source_id=?",c.get("id"))).isEqualByComparingTo("2");
    }
    @Test @Transactional void multiRowMarketRevisionIsAppliedAsOneSet() throws Exception {
        var a=card();buy(a);db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-10-12',2,TRUE)",target);
        long revision=(long)invoke("navRevisionService","createRevisionSet",new Class[]{List.class,String.class,String.class,long.class},List.of(Map.of("fundClassId",fund,"marketDate","2026-10-12","nav","1.25"),Map.of("fundClassId",target,"marketDate","2026-10-12","nav","2.5")),"multi","文件含两产品行",user);
        var p=preview(revision);assertThat((List<?>)p.get("blockers")).isEmpty();apply(execute(p,"multi"));
        assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-12'",fund)).isEqualByComparingTo("1.25");assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-12'",target)).isEqualByComparingTo("2.5");
    }
    @Test @Transactional void historicalRightsUseOriginalEffectiveDate() throws Exception {
        var a=card();buy(a);redeem(a,"500.0000");var p=preview(revision("1.25"));clock("2026-10-20T10:00:00");apply(execute(p,"rights"));
        assertThat(db.scalar("SELECT SUM(e.shares_delta) FROM share_entries e JOIN position_lots l ON l.id=e.lot_id WHERE e.trading_account_id=? AND COALESCE(e.effective_date,IF(e.shares_delta>0,l.acquired_date,DATE(e.created_at)))<='2026-10-13'",a.get("tid"))).isEqualByComparingTo("800");
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("300");
    }
    @Test @Transactional void failureBeforePublicationLeavesOriginalState() throws Exception {
        var a=card();long order=buy(a);var p=preview(revision("1.25"));long run=execute(p,"failure");
        var before=trades.order(user,order);var entries=db.rows("SELECT * FROM share_entries WHERE trading_account_id=? ORDER BY id",a.get("tid"));
        db.update("UPDATE business_number_sequences SET sequence_value=999999 WHERE kind='CONFIRMATION' AND business_date='2026-10-13'");
        var nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(()->nested.execute(status->{try{apply(run);return null;}catch(Exception e){throw new RuntimeException(e);}})).hasRootCauseMessage("当日业务编号序列已用尽");
        assertThat(trades.order(user,order)).isEqualTo(before);assertThat(db.rows("SELECT * FROM share_entries WHERE trading_account_id=? ORDER BY id",a.get("tid"))).isEqualTo(entries);assertThat(db.scalar("SELECT nav FROM nav_records WHERE fund_class_id=? AND date='2026-10-12'",fund)).isEqualByComparingTo("1");
    }
    @Test @Transactional void consecutiveRoundsAndRepeatedExecutionPreserveOneActiveResult() throws Exception {
        var a=card();long order=buy(a);var first=preview(revision("1.25"));long run=execute(first,"round1");apply(run);assertThat(execute(first,"round1")).isEqualTo(run);apply(run);
        var second=preview(revision("2.0"));assertThat((List<?>)second.get("blockers")).isEmpty();
        db.update("UPDATE clearing_runs SET state='COMPLETED',active_scope=NULL WHERE id=?",run);apply(execute(second,"round2"));
        assertThat(db.scalar("SELECT SUM(shares_delta) FROM share_entries WHERE trading_account_id=?",a.get("tid"))).isEqualByComparingTo("500");assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=? AND state='ACTIVE'",order)).isEqualByComparingTo("1");assertThat(db.scalar("SELECT COUNT(*) FROM trade_confirmations WHERE source_type='ORDER' AND source_id=?",order)).isEqualByComparingTo("3");
    }
    @Test @Transactional void unverifiableCashBaselineIsBlockedBeforePublication() throws Exception {
        var a=card();buy(a);db.update("UPDATE wallets SET available=available+1 WHERE trading_account_id=?",a.get("tid"));var p=preview(revision("1.25"));assertThat((List<?>)p.get("blockers")).isNotEmpty();
    }
}
