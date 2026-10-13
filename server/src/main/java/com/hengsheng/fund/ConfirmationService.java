package com.hengsheng.fund;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConfirmationService {
    private final Db db;
    private final BusinessNumberService numbers;
    private final AccountService accounts;
    private final ObjectMapper json;
    public ConfirmationService(Db db,BusinessNumberService numbers,AccountService accounts,ObjectMapper json) {
        this.db=db;this.numbers=numbers;this.accounts=accounts;this.json=json;
    }
    @Transactional public void reverse(long confirmationId,long runId) {
        var row=db.one("SELECT * FROM trade_confirmations WHERE id=? FOR UPDATE",confirmationId);
        if("REVERSED".equals(row.get("state"))){if(row.get("reversedByRunId")==null||Db.id(row.get("reversedByRunId"))!=runId)throw ApiException.conflict("确认结果已由其他轮次冲正");return;}
        db.update("UPDATE trade_confirmations SET state='REVERSED',reversed_by_run_id=? WHERE id=?",runId,confirmationId);
    }
    @Transactional public long record(String sourceType,long sourceId,long roundId,LocalDate confirmationNumberDate,LocalDate effectiveDate,Map<String,Object> result) {
        String table=switch(sourceType) {case "ORDER" -> "orders";case "CONVERSION" -> "conversions";default -> throw ApiException.bad("确认来源类型无效");};
        if(roundId<0 || effectiveDate==null || result==null) throw ApiException.bad("确认轮次或结果无效");
        var source=db.one("SELECT current_confirmation_id FROM "+table+" WHERE id=? FOR UPDATE",sourceId);
        var old=db.rows("SELECT id FROM trade_confirmations WHERE source_type=? AND source_id=? AND round_id=?",sourceType,sourceId,roundId);
        if(!old.isEmpty()) return Db.id(old.getFirst().get("id"));
        String number=numbers.next("CONFIRMATION",confirmationNumberDate);
        long id=db.insert("INSERT INTO trade_confirmations(confirmation_no,source_type,source_id,round_id,confirmation_number_date,effective_date,result,previous_confirmation_id,confirmed_at,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",number,sourceType,sourceId,roundId,confirmationNumberDate,effectiveDate,json.writeValueAsString(Db.jsonDecimals(result)),source.get("currentConfirmationId"),accounts.clock(),LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        numbers.bind("CONFIRMATION",number,sourceType,sourceId,roundId);
        db.update("UPDATE "+table+" SET current_confirmation_id=? WHERE id=?",id,sourceId);
        return id;
    }
}
