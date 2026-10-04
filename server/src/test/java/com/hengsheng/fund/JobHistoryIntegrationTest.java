package com.hengsheng.fund;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class JobHistoryIntegrationTest {
    @Autowired Db db;
    @Autowired CoreController core;
    @Autowired AccountService accounts;
    @Autowired SipService sip;
    @Autowired TradeService trades;
    long userId,accountId,cardId,fundId;
    @BeforeEach void fixture() {
        String suffix=UUID.randomUUID().toString().substring(0,12),name="job"+suffix;
        userId=db.insert("INSERT INTO users(username,password_hash,display_name,role,opened,risk_level,risk_expires_at) VALUES(?,'unused','任务测试','ADMIN',TRUE,5,'2027-12-31')",name);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name,null,java.util.List.of()));
        db.update("UPDATE app_settings SET value='2026-10-16T10:00:00' WHERE name='demo_clock'");
        var card=accounts.bind(Map.of("bankName","模拟银行","holderName","任务测试","requestKey","bind"));
        cardId=Db.id(card.get("id"));accountId=Db.id(card.get("tradingAccountId"));
        fundId=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,min_purchase) VALUES(?,'任务统计基金','BOND','模拟管理人',1,1,1)","JOB"+suffix);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void historyIncludesExecutedSipAndMatchesReturnedCounters() {
        sip.create(userId,Map.of("tradingAccountId",accountId,"bankCardId",cardId,"bindingVersion",1,"fundClassId",fundId,"amount","100.00","frequency","WEEKLY","day",5,"startDate","2026-10-16","requestKey","sip"));
        long before=db.scalar("SELECT MAX(id) FROM job_runs").longValue();
        var result=core.runJobs("SIP",LocalDate.of(2026,10,16));
        assertThat(((Number)result.get("processed")).intValue()).isPositive();
        var rows=db.rows("SELECT * FROM job_runs WHERE id>? ORDER BY id",before);
        assertThat(rows).hasSize(1);
        assertThat(Db.id(rows.getFirst().get("processed"))).isEqualTo(((Number)result.get("processed")).longValue());
        assertThat(result.get("jobId")).isEqualTo(rows.getFirst().get("id"));
    }
    @Test void emptyBatchIsExplicitAndPreservesBusinessTimeSeparatelyFromWallTime() {
        db.update("UPDATE app_settings SET value='2026-01-02T10:00:00' WHERE name='demo_clock'");
        var result=core.runJobs("PAY",LocalDate.of(2026,1,2));
        var row=db.one("SELECT * FROM job_runs ORDER BY id DESC LIMIT 1");
        assertThat(row.get("status")).isEqualTo("NO_WORK");
        assertThat(row.get("createdAt").toString()).startsWith("2026-01-02T10:00");
        assertThat(row.get("actualRunAt")).isNotNull();
        assertThat(LocalDateTime.parse(row.get("actualRunAt").toString())).isBetween(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusMinutes(1),LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusSeconds(2));
        assertThat(result.get("status")).isEqualTo("NO_WORK");
    }
    @Test void newMoneyMarketRowsCountAsSuccessAndAreNotCountedAgain() {
        db.update("UPDATE fund_classes SET type='MONEY' WHERE id=?",fundId);
        db.update("UPDATE app_settings SET value='2026-01-02T16:00:00' WHERE name='demo_clock'");
        long navBefore=db.scalar("SELECT COUNT(*) FROM nav_records").longValue(),incomeBefore=db.scalar("SELECT COUNT(*) FROM money_income_records").longValue();
        var first=core.runJobs("MARKET",LocalDate.of(2026,1,2));
        long added=db.scalar("SELECT COUNT(*) FROM nav_records").longValue()-navBefore+db.scalar("SELECT COUNT(*) FROM money_income_records").longValue()-incomeBefore;
        assertThat(added).isPositive();
        assertThat(((Number)first.get("processed")).longValue()).isEqualTo(added);
        assertThat(core.runJobs("MARKET",LocalDate.of(2026,1,2)).get("processed")).isEqualTo(0);
    }
    @Test void anEarlyScanWithoutDuePlansDoesNotInventWaitingWork() {
        db.update("UPDATE app_settings SET value='2026-01-02T09:10:00' WHERE name='demo_clock'");
        assertThat(sip.run(LocalDate.of(2026,1,2)).get("waiting")).isEqualTo(0);
    }
    @Test void allCreatesExactlyOneCompleteHistoryRecord() {
        db.update("UPDATE app_settings SET value='2026-01-02T16:00:00' WHERE name='demo_clock'");
        long before=db.scalar("SELECT MAX(id) FROM job_runs").longValue();
        var result=core.runJobs("ALL",LocalDate.of(2026,1,2));
        var rows=db.rows("SELECT * FROM job_runs WHERE id>?",before);
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("type")).isEqualTo("ALL");
        for(String name:java.util.List.of("processed","waiting","failed"))
            assertThat(Db.id(rows.getFirst().get(name))).isEqualTo(((Number)result.get(name)).longValue());
    }
    @Test void aStageExceptionIsStoredAsFailureWithItsReason() {
        db.update("UPDATE app_settings SET value='2026-10-16T16:00:00' WHERE name='demo_clock'");
        db.insert("INSERT INTO dividend_events(fund_class_id,record_date,ex_date,payment_date,per_share,created_at) VALUES(?,'2026-10-15','2026-10-16','2026-10-19',9,'2026-10-15')",fundId);
        var result=core.runJobs("MARKET",LocalDate.of(2026,10,16));
        assertThat(((Number)result.get("failed")).intValue()).isPositive();
        var row=db.one("SELECT * FROM job_runs WHERE id=?",result.get("jobId"));
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("details").toString()).contains("净值非正");
    }
    @Test void allDoesNotRetainDividendWaitingAfterItsCashHasArrived() {
        db.update("UPDATE app_settings SET value='2026-01-02T10:00:00' WHERE name='demo_clock'");
        long fundAccount=accounts.linkFundAccount(userId,accountId,fundId);
        trades.addLot(userId,accountId,fundId,cardId,fundAccount,new java.math.BigDecimal("100.0000"),new java.math.BigDecimal("100.00"),LocalDate.of(2025,12,30),"TEST","cash-dividend");
        db.update("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,'2026-01-02',0.9,TRUE)",fundId);
        long event=db.insert("INSERT INTO dividend_events(fund_class_id,record_date,ex_date,payment_date,per_share,created_at) VALUES(?,'2025-12-31','2026-01-02','2026-01-02',0.1,'2025-12-30')",fundId);
        var result=core.runJobs("ALL",LocalDate.of(2026,1,2));
        assertThat(db.one("SELECT status FROM dividend_events WHERE id=?",event).get("status")).isEqualTo("COMPLETED");
        assertThat(db.one("SELECT p.status FROM payouts p JOIN dividend_entitlements d ON d.payout_id=p.id WHERE d.event_id=?",event).get("status")).isEqualTo("PAID");
        assertThat(result.get("waiting")).isEqualTo(0);
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        var history=db.one("SELECT * FROM job_runs WHERE id=?",result.get("jobId"));
        assertThat(Db.id(history.get("waiting"))).isZero();
        assertThat(history.get("details").toString()).doesNotContain("等待现金到账");
    }
}
