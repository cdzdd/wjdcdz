package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class SipService {
    private final AdvancedSupport s;
    SipService(AdvancedSupport s) { this.s=s; }
    @Transactional
    Map<String,Object> create(long userId,Map<String,Object> input) {
        String key=text(input,"requestKey");
        var prior=s.db.rows("SELECT * FROM sip_plans WHERE user_id=? AND request_key=?",userId,key);
        long aid=id(input,"tradingAccountId"),fid=id(input,"fundClassId");
        BigDecimal value=amount(input,"amount",2);
        String frequency=text(input,"frequency");
        int day=Math.toIntExact(id(input,"day")); LocalDate start=date(input.get("startDate"));
        if(!Set.of("WEEKLY","MONTHLY").contains(frequency) || day<1 || day>(frequency.equals("WEEKLY")?7:28))
            throw new IllegalArgumentException("请选择每周1至7或每月1至28日");
        if(!prior.isEmpty()) {
            var p=prior.getFirst();
            if(id(p,"tradingAccountId")!=aid || id(p,"fundClassId")!=fid || decimal(p.get("amount")).compareTo(value)!=0
                    || !frequency.equals(p.get("frequency")) || id(p,"day")!=day || !date(p.get("startDate")).equals(start))
                throw new IllegalArgumentException("请求键已用于其他定投计划");
            return p;
        }
        if(start.isBefore(s.now().toLocalDate())) throw new IllegalArgumentException("定投起始日不能早于当前业务日");
        var account=s.account(userId,aid,true); s.validateCard(input,account);
        var fund=s.eligible(userId,aid,fid,"OPEN");
        if(value.compareTo(decimal(fund.get("minPurchase")))<0) throw new IllegalArgumentException("低于基金申购下限");
        long plan=s.db.insert("INSERT INTO sip_plans(user_id,trading_account_id,bank_card_id,binding_version,fund_class_id,amount,frequency,day,start_date,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",userId,aid,id(account,"bankCardId"),id(account,"bindingVersion"),fid,value,frequency,day,start,key,s.now());
        s.changed("CREATE_SIP","SIP",plan,"创建有明确扣款账户的定投"); return s.db.one("SELECT * FROM sip_plans WHERE id=?",plan);
    }
    @Transactional
    Map<String,Object> change(long userId,long id,Map<String,Object> input) {
        var plan=s.db.one("SELECT * FROM sip_plans WHERE id=? AND user_id=? FOR UPDATE",id,userId);
        if("TERMINATED".equals(plan.get("status"))) throw new IllegalArgumentException("计划已终止");
        String status=String.valueOf(input.getOrDefault("status",plan.get("status")));
        if(!Set.of("ACTIVE","PAUSED","TERMINATED").contains(status)) throw new IllegalArgumentException("定投状态无效");
        long aid=input.containsKey("tradingAccountId")?id(input,"tradingAccountId"):id(plan,"tradingAccountId");
        var account=s.account(userId,aid,false);
        if(input.containsKey("bankCardId")) s.validateCard(input,account);
        BigDecimal value=input.containsKey("amount")?amount(input,"amount",2):decimal(plan.get("amount"));
        int day=input.containsKey("day")?Math.toIntExact(id(input,"day")):Math.toIntExact(id(plan,"day"));
        if(day<1 || day>("WEEKLY".equals(plan.get("frequency"))?7:28)) throw new IllegalArgumentException("定投日期超出范围");
        LocalDate start=date(plan.get("startDate"));
        if("ACTIVE".equals(status) && !"ACTIVE".equals(plan.get("status"))) start=s.now().toLocalDate();
        s.db.update("UPDATE sip_plans SET status=?,trading_account_id=?,bank_card_id=?,binding_version=?,amount=?,day=?,start_date=? WHERE id=?",status,aid,id(account,"bankCardId"),id(account,"bindingVersion"),value,day,start,id);
        s.changed("CHANGE_SIP","SIP",id,status);return s.db.one("SELECT * FROM sip_plans WHERE id=?",id);
    }
    Map<String,Object> run(LocalDate date) {
        int done=0,failed=0,waiting=0;
        var details=new java.util.ArrayList<String>();
        long cursor=0;while(true){var page=s.db.rows("SELECT * FROM sip_plans WHERE status='ACTIVE' AND id>? ORDER BY id LIMIT 200",cursor);if(page.isEmpty())break;
        for(var plan:page) {cursor=id(plan,"id");
                LocalDate period=latestPeriod(plan,date);
                if(period==null) continue;
                long pid=id(plan,"id");LocalDate actualDay=s.now().toLocalDate();
                if(!s.db.rows("SELECT id FROM sip_runs WHERE plan_id=? AND scheduled_date=?",pid,period).isEmpty()) continue;
                if(!s.due(s.openDate(period),10)) { waiting++;details.add("定投计划"+pid+"：尚未到执行日10:00");continue; }
                if(s.db.scalar("SELECT COUNT(*) FROM sip_runs r JOIN orders o ON o.id=r.order_id WHERE r.plan_id=? AND o.status='SUBMITTED'",pid).signum()>0) { waiting++;details.add("定投计划"+pid+"：前一期申购仍待确认");continue; }
                try {
                    s.trades.prepareApplicationDay(id(plan,"fundClassId"));
                    boolean created=s.tx(()-> {
                        var locked=s.db.one("SELECT * FROM sip_plans WHERE id=? FOR UPDATE",pid);
                        if(!"ACTIVE".equals(locked.get("status")) || !s.db.rows("SELECT id FROM sip_runs WHERE plan_id=? AND scheduled_date=?",pid,period).isEmpty()) return false;
                        Map<String,Object> request=new LinkedHashMap<>();
                        request.put("type","PURCHASE");request.put("fundClassId",locked.get("fundClassId"));
                        request.put("tradingAccountId",locked.get("tradingAccountId"));request.put("bankCardId",locked.get("bankCardId"));
                        request.put("bindingVersion",locked.get("bindingVersion"));request.put("amount",locked.get("amount"));
                        request.put("requestKey","SIP-"+pid+"-"+period);
                        var order=s.trades.submit(id(locked,"userId"),request);
                        s.db.insert("INSERT INTO sip_runs(plan_id,scheduled_date,execution_date,status,order_id,created_at) VALUES(?,?,?,'SUBMITTED',?,?)",pid,period,actualDay,id(order,"id"),s.now());return true;
                    });
                    if(created) done++;
                } catch(RuntimeException ex) {
                    int inserted=s.tx(()-> {
                        int count=s.db.update("INSERT IGNORE INTO sip_runs(plan_id,scheduled_date,execution_date,status,reason,created_at) VALUES(?,?,?,'FAILED',?,?)",pid,period,actualDay,String.valueOf(ex.getMessage()).substring(0,Math.min(450,String.valueOf(ex.getMessage()).length())),s.now());
                        if(count>0) s.accounts.notifyUser(id(plan,"userId"),"SIP_FAILED",pid+"-"+period,"定投当期未扣款",String.valueOf(ex.getMessage()));return count;
                    });failed+=inserted;if(inserted>0)details.add("定投计划"+pid+"："+ex.getMessage());
                }
        }}
        return Map.of("processed",done,"waiting",waiting,"failed",failed,"details",details);
    }
    private LocalDate latestPeriod(Map<String,Object> plan,LocalDate through) {
        LocalDate start=date(plan.get("startDate"));
        for(LocalDate day=through;!day.isBefore(start);day=day.minusDays(1)) {
            boolean matches="WEEKLY".equals(plan.get("frequency"))?day.getDayOfWeek().getValue()==id(plan,"day"):day.getDayOfMonth()==id(plan,"day");
            if(matches && !s.openDate(day).isAfter(through)) return day;
        }
        return null;
    }
}
