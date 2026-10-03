package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
public class AdvancedService {
    private final AdvancedSupport s;
    private final SipService sip;
    private final DividendService dividends;
    private final ConversionService conversions;
    private final MoneyIncomeService money;
    public AdvancedService(AdvancedSupport s,SipService sip,DividendService dividends,ConversionService conversions,MoneyIncomeService money) {
        this.s=s;this.sip=sip;this.dividends=dividends;this.conversions=conversions;this.money=money;
    }
    public Map<String,Object> run(String type,LocalDate date) {
        if(date.isAfter(s.now().toLocalDate())) throw new IllegalArgumentException("不能执行未来业务日");
        int processed=0,waiting=0,failed=0;
        var details=new ArrayList<String>();
        if(type.equals("MARKET") || type.equals("ALL")) marketIncome(date.minusDays(1));
        if(type.equals("SIP") || type.equals("ALL")) {
            var result=sip.run(date);processed+=number(result,"processed");waiting+=number(result,"waiting");failed+=number(result,"failed");
        }
        if(type.equals("CONFIRM") || type.equals("ALL")) {
            var result=conversions.run(date);processed+=number(result,"processed");waiting+=number(result,"waiting");
            for(var event:s.db.rows("SELECT * FROM dividend_events WHERE status!='COMPLETED' AND record_date<? ORDER BY record_date,id",date.plusDays(1))) {
                try {
                    if("DRAFT".equals(event.get("status"))) dividends.snapshot(id(event,"id"));
                    var result2=dividends.execute(id(event,"id"));
                    processed+=number(result2,"processed");waiting+=number(result2,"waiting");
                } catch(IllegalArgumentException ex) { waiting++;details.add(ex.getMessage()); }
            }
        }
        if(type.equals("MONEY") || type.equals("ALL")) {
            var result=money.run(date.minusDays(1));processed+=number(result,"processed");waiting+=number(result,"waiting");failed+=number(result,"failed");
            if(result.get("details") instanceof java.util.List<?> rows) rows.forEach(value->details.add(value.toString()));
        }
        syncPayments();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("processed",processed);result.put("waiting",waiting);result.put("failed",failed);result.put("details",details);return result;
    }
    private int number(Map<String,Object> row,String name) { return Integer.parseInt(row.getOrDefault(name,0).toString()); }
    private void marketIncome(LocalDate through) {
        for(var fund:s.db.rows("SELECT id FROM fund_classes WHERE type='MONEY' AND state IN ('OPEN','TERMINATED')")) {
            LocalDate start=through.minusDays(30);
            var first=s.db.rows("SELECT MIN(acquired_date) first_date FROM position_lots WHERE fund_class_id=?",fund.get("id"));
            if(!first.isEmpty() && first.getFirst().get("firstDate")!=null) start=date(first.getFirst().get("firstDate"));
            for(LocalDate day=start;!day.isAfter(through);day=day.plusDays(1)) {
                int noise=Math.floorMod((int)(id(fund,"id")*17+day.toEpochDay()),21);
                var value=new java.math.BigDecimal("0.50").add(new java.math.BigDecimal(noise).movePointLeft(2));
                s.db.update("INSERT IGNORE INTO money_income_records(fund_class_id,date,income_per_ten_thousand,annual_yield) VALUES(?,?,?,?)",fund.get("id"),day,value,new java.math.BigDecimal("0.021"));
            }
        }
    }
    @Transactional
    public void syncPayments() {
        s.db.update("UPDATE offering_orders f JOIN payouts p ON p.source_type='OFFERING_REFUND' AND p.source_id=CAST(f.order_id AS CHAR) AND p.trading_account_id=f.trading_account_id SET f.status='COMPLETED' WHERE p.status='PAID' AND f.status='REFUND_PENDING'");
        s.db.update("UPDATE orders o JOIN offering_orders f ON f.order_id=o.id SET o.status='COMPLETED',o.reason='募集退款已原路到账' WHERE f.status='COMPLETED' AND o.status='CONFIRMED' AND o.type='OFFERING'");
        s.db.update("UPDATE dividend_entitlements d JOIN payouts p ON p.id=d.payout_id SET d.status='COMPLETED' WHERE p.status='PAID' AND d.status='PENDING'");
        s.db.update("UPDATE dividend_events e SET e.status='COMPLETED' WHERE e.status!='DRAFT' AND NOT EXISTS(SELECT 1 FROM dividend_entitlements d WHERE d.event_id=e.id AND d.status='PENDING')");
    }
}
