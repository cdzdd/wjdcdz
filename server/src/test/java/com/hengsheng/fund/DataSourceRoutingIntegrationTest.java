package com.hengsheng.fund;

import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DataSourceRoutingIntegrationTest {
    @Autowired Db db;
    @Autowired AccountService accounts;
    @Autowired TradeService trades;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource routing;
    @Autowired @Qualifier("batchDataSource") HikariDataSource batch;
    @Autowired @Qualifier("realtimeDataSource") HikariDataSource realtime;
    @Test void batchPoolCannotConsumeRealtimeQuota() throws Exception {
        try(var first=batch.getConnection();var second=batch.getConnection()){
            assertThat(batch.getHikariPoolMXBean().getActiveConnections()).isEqualTo(2);
            try(var live=routing.getConnection()){
                assertThat(live.isValid(1)).isTrue();
                assertThat(realtime.getHikariPoolMXBean().getActiveConnections()).isPositive();
            }
        }
        ClearingExecutionContext.enter(0,LocalDateTime.of(2026,1,2,10,0),null);
        try(var routed=routing.getConnection()){
            assertThat(batch.getHikariPoolMXBean().getActiveConnections()).isPositive();
            assertThat(realtime.getHikariPoolMXBean().getActiveConnections()).isZero();
        }finally{ClearingExecutionContext.clear();}
        assertThat(ClearingExecutionContext.batch()).isFalse();
    }
    @Test void batchFailureRollsBackWalletPositionAndEntries(){
        String name="route"+UUID.randomUUID().toString().substring(0,10);
        long uid=db.insert("INSERT INTO users(username,password_hash,display_name,role,opened) VALUES(?,'unused','路由测试','ADMIN',TRUE)",name);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name,null,List.of()));
        try{
            var card=accounts.bind(Map.of("bankName","模拟银行","holderName","路由测试","requestKey","bind"));long tid=Db.id(card.get("tradingAccountId")),cid=Db.id(card.get("id"));
            long fid=Db.id(db.one("SELECT id FROM fund_classes ORDER BY id LIMIT 1").get("id")),fa=accounts.linkFundAccount(uid,tid,fid);
            var before=db.one("SELECT available FROM wallets WHERE trading_account_id=?",tid);long entries=db.scalar("SELECT COUNT(*) FROM cash_entries WHERE trading_account_id=?",tid).longValue();
            ClearingExecutionContext.enter(0,LocalDateTime.of(2026,1,2,10,0),uid);
            try{
                assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                    db.update("UPDATE wallets SET available=available-100 WHERE trading_account_id=?",tid);
                    accounts.cash(uid,tid,"TEST","rollback","DEBIT",new BigDecimal("-100.00"),BigDecimal.ZERO);
                    trades.addLot(uid,tid,fid,cid,fa,new BigDecimal("100.0000"),new BigDecimal("100.00"),LocalDate.of(2026,1,2),"TEST","rollback");
                    throw new IllegalStateException("forced failure");
                })).isInstanceOf(IllegalStateException.class).hasMessage("forced failure");
            }finally{ClearingExecutionContext.clear();}
            assertThat(db.one("SELECT available FROM wallets WHERE trading_account_id=?",tid)).isEqualTo(before);
            assertThat(db.scalar("SELECT COUNT(*) FROM position_lots WHERE trading_account_id=?",tid)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(db.scalar("SELECT COUNT(*) FROM cash_entries WHERE trading_account_id=?",tid).longValue()).isEqualTo(entries);
            assertThat(db.scalar("SELECT COUNT(*) FROM share_entries WHERE trading_account_id=?",tid)).isEqualByComparingTo(BigDecimal.ZERO);
        }finally{SecurityContextHolder.clearContext();ClearingExecutionContext.clear();}
    }
    @Test void routingCannotChangeInsideAnExistingTransaction(){
        new TransactionTemplate(manager).executeWithoutResult(status->assertThatThrownBy(()->ClearingExecutionContext.enter(1,LocalDateTime.now(),null)).isInstanceOf(IllegalStateException.class));
        assertThat(ClearingExecutionContext.batch()).isFalse();
    }
}
