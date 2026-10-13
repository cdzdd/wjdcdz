package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class BatchPagingIntegrationTest {
    @Autowired Db db;
    @Autowired FundService funds;
    @Autowired CoreController core;
    @Test void marketIncludesEveryProductAcrossThreePages() {
        db.update("UPDATE app_settings SET value='2026-01-02T16:00:00' WHERE name='demo_clock'");
        db.update("INSERT INTO trading_calendar(date,open) VALUES('2026-01-02',TRUE) ON DUPLICATE KEY UPDATE open=TRUE");
        String prefix="PG"+UUID.randomUUID().toString().substring(0,8);
        for(int i=0;i<401;i++)db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,state) VALUES(?,'分页行情','BOND','测试',1,1,'OPEN')",prefix+i);
        funds.market(LocalDate.of(2026,1,2));
        assertThat(db.scalar("SELECT COUNT(*) FROM nav_records n JOIN fund_classes f ON f.id=n.fund_class_id WHERE f.code LIKE ? AND n.date='2026-01-02'",prefix+"%").intValue()).isEqualTo(401);
        assertThat(funds.market(LocalDate.of(2026,1,2)).get("processed")).isEqualTo(0);
    }
    @Test void oneBadProductDoesNotLoseCommittedCountsOrStopLaterProducts(){
        db.update("UPDATE app_settings SET value='2026-01-02T16:00:00' WHERE name='demo_clock'");
        db.update("INSERT INTO trading_calendar(date,open) VALUES('2026-01-02',TRUE) ON DUPLICATE KEY UPDATE open=TRUE");
        String prefix="FAIL"+UUID.randomUUID().toString().substring(0,8);
        long bad=0,last=0;
        for(int i=0;i<301;i++){
            long id=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,state) VALUES(?,'行情失败隔离','BOND','测试',1,1,'OPEN')",prefix+i);
            if(i==100)bad=id;if(i==300)last=id;
        }
        db.insert("INSERT INTO dividend_events(fund_class_id,record_date,ex_date,payment_date,per_share,created_at) VALUES(?,'2025-12-31','2026-01-02','2026-01-02',9,'2025-12-30')",bad);
        long before=db.scalar("SELECT COUNT(*) FROM nav_records").longValue(),incomeBefore=db.scalar("SELECT COUNT(*) FROM money_income_records").longValue();
        var result=core.runJobs("MARKET",LocalDate.of(2026,1,2));
        assertThat(db.scalar("SELECT COUNT(*) FROM nav_records WHERE fund_class_id=? AND date='2026-01-02'",last).intValue()).isEqualTo(1);
        assertThat(db.scalar("SELECT COUNT(*) FROM nav_records n JOIN fund_classes f ON f.id=n.fund_class_id WHERE f.code LIKE ? AND n.date='2026-01-02'",prefix+"%").intValue()).isEqualTo(300);
        long saved=db.scalar("SELECT COUNT(*) FROM nav_records").longValue()-before+db.scalar("SELECT COUNT(*) FROM money_income_records").longValue()-incomeBefore;
        assertThat(((Number)result.get("processed")).longValue()).isEqualTo(saved);
        assertThat(result.get("failed")).isEqualTo(1);assertThat(result.get("status")).isEqualTo("PARTIAL");
    }
}
