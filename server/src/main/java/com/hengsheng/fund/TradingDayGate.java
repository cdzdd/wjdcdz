package com.hengsheng.fund;

import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared submission fence; the export snapshot takes the exclusive row lock. */
@Service
public class TradingDayGate {
    private final Db db;
    public TradingDayGate(Db db){this.db=db;}
    /** Called before entering the long asset transaction; the metadata insert commits immediately. */
    public void prepare(LocalDate date){
        if(!db.rows("SELECT business_date FROM application_days WHERE business_date=?",date).isEmpty())return;
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("请在资产事务开始前准备申请归属日");
        db.update("INSERT IGNORE INTO application_days(business_date,state) VALUES(?,'OPEN')",date);
    }
    public void enter(LocalDate date){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("申请封账门禁必须在业务事务内使用");
        var day=db.one("SELECT state FROM application_days WHERE business_date=? FOR SHARE",date);
        if("SEALED".equals(day.get("state")))throw ApiException.conflict("该申请归属日已经封账，请选择新的开放日办理业务");
    }
}
