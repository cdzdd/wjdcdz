package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test")
class MoneyIncomeConcurrencyTest {
    @Autowired Db db;
    @Autowired TradeService trades;
    @Autowired MoneyIncomeService money;
    @MockitoSpyBean AccountService accounts;
    final List<Long> users=new ArrayList<>();
    Map<String,Object> card(){
        String key=UUID.randomUUID().toString();long uid=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','货基并发',TRUE,5,'2027-12-31')",key);users.add(uid);
        long cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'货基并发',?)",uid,key,key);
        long aid=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",uid,key,cid);
        db.update("INSERT INTO wallets(trading_account_id,available) VALUES(?,1000)",aid);db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",aid,cid);
        db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,'INITIAL',?,'CREDIT',1000,0,1000,'2026-10-12')",uid,aid,key);
        return Map.of("uid",uid,"id",cid,"aid",aid);
    }
    @Test void waitingIncomeAccountDoesNotHoldFundLockAgainstAnotherAccount() throws Exception {
        String original=db.one("SELECT value FROM app_settings WHERE name='demo_clock'").get("value").toString();long fid=0;
        try(var workers=Executors.newFixedThreadPool(2)){
            db.update("UPDATE app_settings SET value='2026-10-14T00:10:00' WHERE name='demo_clock'");
            fid=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level) VALUES(?,'货基并发','MONEY','测试',1,1)","MI"+UUID.randomUUID().toString().substring(0,12));
            var a=card();var b=card();long fundId=fid,uid=Db.id(a.get("uid")),aid=Db.id(a.get("aid"));
            long fa=accounts.linkFundAccount(uid,aid,fid);trades.addLot(uid,aid,fid,Db.id(a.get("id")),fa,new BigDecimal("100"),new BigDecimal("100"),LocalDate.of(2026,10,12),"TEST",UUID.randomUUID().toString());
            db.insert("INSERT INTO money_income_records(fund_class_id,date,income_per_ten_thousand) VALUES(?,'2026-10-13',1)",fid);
            trades.prepareApplicationDay(fid);var reached=new CountDownLatch(1);
            doAnswer(call->{if(Thread.currentThread().getName().equals("income-lock-test"))reached.countDown();return call.callRealMethod();}).when(accounts).lockAccount(uid,aid);
            try(var locked=db.jdbc.getDataSource().getConnection()){
                locked.setAutoCommit(false);try(var p=locked.prepareStatement("SELECT id FROM trading_accounts WHERE id=? FOR UPDATE")){p.setLong(1,aid);p.executeQuery().close();}
                var income=workers.submit(()->{Thread.currentThread().setName("income-lock-test");return money.run(LocalDate.of(2026,10,13));});
                assertThat(reached.await(10,TimeUnit.SECONDS)).isTrue();
                var purchase=workers.submit(()->trades.submit(Db.id(b.get("uid")),Map.of("type","PURCHASE","fundClassId",fundId,"tradingAccountId",b.get("aid"),"bankCardId",b.get("id"),"bindingVersion",1,"amount","100.00","requestKey",UUID.randomUUID().toString())));
                try{assertThat(purchase.get(2,TimeUnit.SECONDS).get("status")).isEqualTo("SUBMITTED");assertThat(income.isDone()).isFalse();}
                finally{locked.rollback();income.get(20,TimeUnit.SECONDS);purchase.get(20,TimeUnit.SECONDS);}
            }
        }finally{
            reset(accounts);
            for(long uid:users){db.update("DELETE FROM notifications WHERE user_id=?",uid);db.update("DELETE FROM business_number_registry WHERE source_type='ORDER' AND source_id IN(SELECT id FROM orders WHERE user_id=?)",uid);db.update("DELETE FROM extension_replay_inputs WHERE trading_account_id IN(SELECT id FROM trading_accounts WHERE user_id=?)",uid);for(String table:List.of("money_income_snapshots","order_lot_allocations","order_events")){if(table.equals("money_income_snapshots"))db.update("DELETE FROM "+table+" WHERE user_id=?",uid);else db.update("DELETE FROM "+table+" WHERE order_id IN(SELECT id FROM orders WHERE user_id=?)",uid);}for(String table:List.of("cash_entries","share_entries","orders","position_lots"))db.update("DELETE FROM "+table+" WHERE user_id=?",uid);for(String table:List.of("trading_fund_accounts","card_bindings","wallets"))db.update("DELETE FROM "+table+" WHERE trading_account_id IN(SELECT id FROM trading_accounts WHERE user_id=?)",uid);for(String table:List.of("trading_accounts","fund_accounts","bank_cards"))db.update("DELETE FROM "+table+" WHERE user_id=?",uid);db.update("DELETE FROM users WHERE id=?",uid);}
            if(fid!=0){db.update("DELETE FROM money_income_records WHERE fund_class_id=?",fid);db.update("DELETE FROM fund_classes WHERE id=?",fid);}db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",original);
        }
    }
}
