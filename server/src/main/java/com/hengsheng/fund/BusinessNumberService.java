package com.hengsheng.fund;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessNumberService {
    private final Db db;
    private final AccountService accounts;
    public BusinessNumberService(Db db,AccountService accounts) {this.db=db;this.accounts=accounts;}
    @Transactional public String next(String kind,LocalDate businessDate) {
        if(kind==null || !Set.of("APPLICATION","CONFIRMATION").contains(kind) || businessDate==null)
            throw ApiException.bad("编号类型或业务日期无效");
        if(db.rows("SELECT date FROM trading_calendar WHERE date=? AND open=TRUE",businessDate).isEmpty())
            throw ApiException.bad("编号归属日期必须是已登记工作日");
        db.update("INSERT INTO business_number_sequences(kind,business_date,sequence_value) VALUES(?,?,0) ON DUPLICATE KEY UPDATE sequence_value=sequence_value",kind,businessDate);
        long next=Db.id(db.one("SELECT sequence_value FROM business_number_sequences WHERE kind=? AND business_date=? FOR UPDATE",kind,businessDate).get("sequenceValue"))+1;
        if(next>999999) throw ApiException.conflict("当日业务编号序列已用尽");
        String number=businessDate.format(DateTimeFormatter.BASIC_ISO_DATE)+String.format(Locale.ROOT,"%06d",next);
        db.update("UPDATE business_number_sequences SET sequence_value=? WHERE kind=? AND business_date=?",next,kind,businessDate);
        db.insert("INSERT INTO business_number_registry(kind,business_no,business_date,sequence_no,created_at,business_time) VALUES(?,?,?,?,?,?)",kind,number,businessDate,next,LocalDateTime.now(ZoneId.of("Asia/Shanghai")),accounts.clock());
        return number;
    }
    public void bind(String kind,String number,String sourceType,long sourceId,long roundId) {
        if(db.update("UPDATE business_number_registry SET source_type=?,source_id=?,round_id=? WHERE kind=? AND business_no=? AND source_type IS NULL",sourceType,sourceId,roundId,kind,number)!=1)
            throw ApiException.conflict("业务编号来源登记失败");
    }
}
