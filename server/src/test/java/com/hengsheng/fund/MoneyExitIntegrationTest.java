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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class MoneyExitIntegrationTest {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired TradeService trades;
    @Autowired MoneyIncomeService money;
    long user,fund,account,card,lot;
    String key;
    @BeforeEach void fixture() {
        assertThat(db.scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CONNECTION_ID() AND DB='fund_sim_test'")).isEqualByComparingTo("1");
        key=UUID.randomUUID().toString().replace("-","").substring(0,18);admin();clock("2026-10-12T14:00:00");
        user=db.insert("INSERT INTO users(username,password_hash,display_name,opened,risk_level,risk_expires_at) VALUES(?,'unused','清户测试',TRUE,5,'2027-10-12')","mx"+key);
        fund=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,confirmation_days,payment_days) VALUES(?,'末期收益测试','MONEY','模拟管理人',1,1,1,1)","MX"+key);
        card=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'清户测试',?)",user,"SIM-"+key,key);
        account=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",user,"MX-"+key,card);
        db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",account);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",account,card);
        long fa=accounts.linkFundAccount(user,account,fund);
        lot=trades.addLot(user,account,fund,card,fa,new BigDecimal("1000"),new BigDecimal("1000"),LocalDate.of(2026,10,12),"TEST_OPEN",key);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void admin(){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("admin","", "ROLE_ADMIN"));}
    void investor(){SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("mx"+key,"", "ROLE_INVESTOR"));}
    void clock(String value){db.update("UPDATE app_settings SET value=? WHERE name='demo_clock'",value);}
    void rate(String day,String value){admin();money.write(Map.of("fundClassId",fund,"date",day,"incomePerTenThousand",value,"annualYield","0.02"));}
    long redemption(){return Db.id(trades.submit(user,Map.of("type","REDEMPTION","fundClassId",fund,"tradingAccountId",account,"shares","1000.0000","requestKey",key)).get("id"));}
    void confirmAndWithdrawPrincipal(long order) {
        clock("2026-10-13T10:00:00");assertThat(trades.confirmOne(order)).isTrue();
        long payout=Db.id(db.one("SELECT id FROM payouts WHERE source_type='REDEMPTION' AND source_id=?",Long.toString(order)).get("id"));
        assertThat(trades.payOne(payout)).isTrue();investor();
        accounts.cashOperation(account,Map.of("amount","1000.00","requestKey","principal-withdraw"),false);admin();
    }
    @Test void fullRedemptionPaysFinalIncomeAsCashOnceWithoutRecreatingAnUnredeemableLot() {
        confirmAndWithdrawPrincipal(redemption());rate("2026-10-13","1.23456");clock("2026-10-14T00:10:00");
        money.run(LocalDate.of(2026,10,13));
        assertThat(db.scalar("SELECT COALESCE(SUM(shares),0) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",account,fund)).isZero();
        var snapshot=db.one("SELECT * FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-13'",account);
        assertThat(Db.decimal(snapshot.get("income"))).isEqualByComparingTo("0.123456");
        assertThat(Db.decimal(snapshot.get("tail"))).isZero();assertThat(Db.decimal(snapshot.get("appliedShares"))).isZero();
        long payout=Db.id(snapshot.get("payoutId"));
        assertThat(db.scalar("SELECT amount FROM payouts WHERE id=?",payout)).isEqualByComparingTo("0.12");
        assertThat(db.scalar("SELECT original_tail FROM money_exit_disposals WHERE snapshot_id=?",snapshot.get("id"))).isEqualByComparingTo("0.003456");
        money.run(LocalDate.of(2026,10,13));
        assertThat(db.scalar("SELECT COUNT(*) FROM payouts WHERE source_type='MONEY_EXIT_INCOME' AND trading_account_id=?",account)).isEqualByComparingTo("1");
        assertThat(trades.payOne(payout)).isFalse();clock("2026-10-14T10:00:00");assertThat(trades.payOne(payout)).isTrue();
        investor();accounts.cashOperation(account,Map.of("amount","0.12","requestKey","income-withdraw"),false);
        assertThat(money.hasUnsettledForAccount(account)).isFalse();
        assertThatCode(()->accounts.unbind(card,Map.of())).doesNotThrowAnyException();
    }
    @Test void lastNaturalDayIncomeBlocksUnbindButAnotherAccountPendingDoesNot() {
        confirmAndWithdrawPrincipal(redemption());investor();
        assertThat(money.hasUnsettledForAccount(account)).isTrue();
        assertThatThrownBy(()->accounts.unbind(card,Map.of())).isInstanceOf(ApiException.class);
        admin();long otherUser=db.insert("INSERT INTO users(username,password_hash,display_name,opened) VALUES(?,'unused','其它客户',TRUE)","other"+key);
        long otherCard=db.insert("INSERT INTO bank_cards(user_id,bank_name,sim_number,holder_name,request_key) VALUES(?,'模拟银行',?,'其它客户',?)",otherUser,"SIM-OTHER-"+key,key);
        long otherAccount=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id) VALUES(?,?,?)",otherUser,"OTHER-"+key,otherCard);
        db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",otherAccount);
        long otherFa=accounts.linkFundAccount(otherUser,otherAccount,fund);
        long otherLot=trades.addLot(otherUser,otherAccount,fund,otherCard,otherFa,new BigDecimal("50"),new BigDecimal("50"),LocalDate.of(2026,10,11),"TEST_OTHER",key);
        db.update("UPDATE position_lots SET frozen_shares=50 WHERE id=?",otherLot);
        db.update("INSERT INTO money_income_snapshots(date,user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,income,status) VALUES('2026-10-12',?,?,?,?,?,50,-10,'PENDING')",otherUser,otherAccount,fund,otherCard,otherFa);
        rate("2026-10-12","-2000");rate("2026-10-13","1");clock("2026-10-14T10:00:00");money.run(LocalDate.of(2026,10,13));
        long payout=Db.id(db.one("SELECT id FROM payouts WHERE source_type='MONEY_EXIT_INCOME' AND trading_account_id=?",account).get("id"));
        assertThat(trades.payOne(payout)).isTrue();investor();accounts.cashOperation(account,Map.of("amount","0.10","requestKey","income-withdraw"),false);
        assertThat(money.hasUnsettledForAccount(otherAccount)).isTrue();assertThat(money.hasUnsettledForAccount(account)).isFalse();
        assertThatCode(()->accounts.unbind(card,Map.of())).doesNotThrowAnyException();
    }
    @Test void fullRedemptionFreezeDefersReinvestmentUntilConfirmationThenExitsAsCash() {
        db.update("UPDATE position_lots SET acquired_date='2026-10-09' WHERE id=?",lot);
        rate("2026-10-10","0");rate("2026-10-11","0");money.run(LocalDate.of(2026,10,11));
        long order=redemption();rate("2026-10-12","1");clock("2026-10-13T00:10:00");money.run(LocalDate.of(2026,10,12));
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=?",account)).isEqualByComparingTo("1000");
        assertThat(db.one("SELECT status FROM money_income_snapshots WHERE trading_account_id=? AND date='2026-10-12'",account).get("status")).isEqualTo("PENDING");
        confirmAndWithdrawPrincipal(order);money.run(LocalDate.of(2026,10,12));
        assertThat(db.scalar("SELECT SUM(shares) FROM position_lots WHERE trading_account_id=?",account)).isZero();
        assertThat(db.scalar("SELECT amount FROM payouts WHERE source_type='MONEY_EXIT_INCOME' AND trading_account_id=?",account)).isEqualByComparingTo("0.10");
    }
}
