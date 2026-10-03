package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class OfferingService {
    private final AdvancedSupport s;
    OfferingService(AdvancedSupport s) { this.s=s; }

    @Transactional
    Map<String,Object> subscribe(long userId, Map<String,Object> input) {
        String key=text(input,"requestKey"),fingerprint=TradeService.fingerprint(input);
        long aid=id(input,"tradingAccountId"),fid=id(input,"fundClassId");
        s.db.one("SELECT id FROM fund_classes WHERE id=? FOR UPDATE",fid);
        s.db.one("SELECT id FROM users WHERE id=? FOR UPDATE",userId);
        var old=s.db.rows("SELECT * FROM orders WHERE user_id=? AND request_key=?",userId,key);
        if(!old.isEmpty()) {
            var order=old.getFirst();
            String stored=String.valueOf(order.get("requestFingerprint"));
            if(!"OFFERING".equals(order.get("type")) || aid!=id(order,"tradingAccountId") || fid!=id(order,"fundClassId")
                    || amount(input,"amount",2).compareTo(decimal(order.get("amount")))!=0
                    || id(input,"bankCardId")!=id(order,"sourceCardId") || id(input,"bindingVersion")!=id(order,"bindingVersion")
                    || (stored.length()==64 && !stored.equals(fingerprint)))
                throw new IllegalArgumentException("重复请求键与原认购不一致");
            return order;
        }
        if(!s.db.rows("SELECT id FROM offering_events WHERE fund_class_id=?",fid).isEmpty()) throw ApiException.conflict("募集结果已固定，不再受理新认购");
        var account=s.account(userId,aid,true); s.validateCard(input,account);
        var fund=s.eligible(userId,aid,fid,"RAISING");
        if("MONEY".equals(fund.get("type"))) throw new IllegalArgumentException("货币样本不支持认购");
        BigDecimal value=amount(input,"amount",2);
        if(value.compareTo(decimal(fund.get("minPurchase")))<0) throw new IllegalArgumentException("低于认购下限");
        BigDecimal purchaseRate=decimal(fund.get("purchaseRate")),fixedFee=null;
        var tiers=s.db.rows("SELECT rate,fixed_fee FROM fee_tiers WHERE fund_class_id=? AND min_amount<=? ORDER BY min_amount DESC LIMIT 1",fid,value);
        if(!tiers.isEmpty()) { purchaseRate=decimal(tiers.getFirst().get("rate"));if(tiers.getFirst().get("fixedFee")!=null) fixedFee=decimal(tiers.getFirst().get("fixedFee")); }
        FundMath.purchase(value,purchaseRate,fixedFee,BigDecimal.ONE);
        long fa=s.accounts.linkFundAccount(userId,aid,fid);
        LocalDate day=s.openDate(s.now().toLocalDate());
        long order=s.db.insert("INSERT INTO orders(order_no,user_id,type,fund_class_id,trading_account_id,source_card_id,binding_version,fund_account_id,amount,application_date,confirmation_date,payment_date,request_key,request_fingerprint,purchase_rate,redemption_rate,fixed_fee,risk_level,rule_version,cutoff_time,created_at) VALUES(?,?,'OFFERING',?,?,?,?,?,?, ?,?,?, ?,?,?,?, ?,?,?,?,?)",
                "OF"+UUID.randomUUID().toString().substring(0,16),userId,fid,aid,id(account,"bankCardId"),id(account,"bindingVersion"),fa,value,day,day,day,key,fingerprint,purchaseRate,decimal(fund.get("redemptionRate")),fixedFee,id(fund,"riskLevel"),id(fund,"ruleVersion"),fund.get("cutoffTime"),s.now());
        s.debit(userId,aid,value,"OFFERING",Long.toString(order));
        s.db.insert("INSERT INTO offering_orders(order_id,user_id,trading_account_id,fund_class_id,source_card_id,created_at) VALUES(?,?,?,?,?,?)",order,userId,aid,fid,id(account,"bankCardId"),s.now());
        s.changed("SUBSCRIBE","ORDER",order,"新基金模拟认购");
        return s.db.one("SELECT * FROM orders WHERE id=?",order);
    }

    Map<String,Object> finalizeOffering(long fundId,Map<String,Object> input) {
        String outcome=text(input,"outcome");
        if(!outcome.equals("ESTABLISHED") && !outcome.equals("FAILED")) throw new IllegalArgumentException("募集结果无效");
        LocalDate end=date(input.get("endDate"));
        if(end.isAfter(s.now().toLocalDate())) throw new IllegalArgumentException("募集结束日期尚未到达");
        BigDecimal nav=amount(input,"initialNav",6);
        BigDecimal rate=decimal(input.getOrDefault("interestRate","0"));
        if(rate.signum()<0 || rate.compareTo(BigDecimal.ONE)>0 || rate.scale()>8) throw new IllegalArgumentException("模拟利率应介于0和1且最多八位小数");
        var registered=s.tx(()-> {
            var fund=s.db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fundId);
            var old=s.db.rows("SELECT * FROM offering_events WHERE fund_class_id=?",fundId);
            if(!old.isEmpty()) {
                var row=old.getFirst();
                if(!outcome.equals(row.get("outcome")) || !end.equals(date(row.get("endDate"))) || nav.compareTo(decimal(row.get("initialNav")))!=0 || rate.compareTo(decimal(row.get("interestRate")))!=0)
                    throw new IllegalArgumentException("已登记募集结果不能修改");
                return row;
            }
            if(!"RAISING".equals(fund.get("state"))) throw new IllegalArgumentException("基金不在募集期");
            if(s.db.scalar("SELECT COUNT(*) FROM orders o JOIN offering_orders f ON f.order_id=o.id WHERE f.fund_class_id=? AND f.status='SUBMITTED' AND o.application_date>?",fundId,end).signum()>0)
                throw ApiException.bad("募集结束日期不能早于已受理认购的申请日期");
            if(outcome.equals("ESTABLISHED")) publishInitialNav(fundId,nav,s.now().toLocalDate());
            long event=s.db.insert("INSERT INTO offering_events(fund_class_id,outcome,end_date,initial_nav,interest_rate,created_at) VALUES(?,?,?,?,?,?)",fundId,outcome,end,nav,rate,s.now());
            return s.db.one("SELECT * FROM offering_events WHERE id=?",event);
        });
        if("COMPLETED".equals(registered.get("status"))) return Map.of("processed",0,"waiting",0,"failed",0);
        long event=id(registered,"id");
        int count=0;
        for(var row:s.db.rows("SELECT o.* FROM orders o JOIN offering_orders f ON f.order_id=o.id WHERE f.fund_class_id=? AND f.status='SUBMITTED' ORDER BY o.id",fundId)) {
            if(s.tx(()->finishOne(id(row,"id"),outcome,end,nav,rate))) count++;
        }
        s.tx(()-> {
            s.db.update("UPDATE offering_events SET status='COMPLETED' WHERE id=?",event);
            s.db.update("UPDATE fund_classes SET state=?,purchase_open=FALSE,redemption_open=FALSE WHERE id=? AND state='RAISING'",outcome.equals("ESTABLISHED")?"BUILDING":"FAILED",fundId);
            s.changed("FINALIZE_OFFERING","FUND",fundId,outcome); return true;
        });
        return Map.of("processed",count,"waiting",0,"failed",0);
    }
    private void publishInitialNav(long fundId,BigDecimal nav,LocalDate actualDay) {
        var existing=s.db.rows("SELECT nav FROM nav_records WHERE fund_class_id=? AND date=? FOR UPDATE",fundId,actualDay);
        if(!existing.isEmpty() && decimal(existing.getFirst().get("nav")).compareTo(nav)!=0) throw ApiException.conflict("实际成立日已有不同净值，请先核对成立参数");
        s.db.update("INSERT INTO nav_records(fund_class_id,date,nav,published,used) VALUES(?,?,?,TRUE,TRUE) ON DUPLICATE KEY UPDATE published=TRUE,used=TRUE",fundId,actualDay,nav);
    }

    private boolean finishOne(long orderId,String outcome,LocalDate end,BigDecimal nav,BigDecimal rate) {
        var order=s.db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",orderId);
        var offering=s.db.one("SELECT * FROM offering_orders WHERE order_id=? FOR UPDATE",orderId);
        if(!"SUBMITTED".equals(offering.get("status"))) return false;
        BigDecimal value=decimal(order.get("amount"));
        BigDecimal interest=AdvancedAmounts.interest(value,rate,date(order.get("applicationDate")),end);
        LocalDate actualDay=s.now().toLocalDate();
        if(outcome.equals("ESTABLISHED")) {
            var calculation=FundMath.purchase(value,decimal(order.get("purchaseRate")),order.get("fixedFee")==null?null:decimal(order.get("fixedFee")),nav);
            BigDecimal net=calculation.netAmount(),fee=calculation.fee();
            BigDecimal shares=net.add(interest).divide(nav,4,RoundingMode.HALF_UP);
            publishInitialNav(id(order,"fundClassId"),nav,actualDay);
            s.trades.addLot(id(order,"userId"),id(order,"tradingAccountId"),id(order,"fundClassId"),id(order,"sourceCardId"),id(order,"fundAccountId"),shares,value,actualDay,"OFFERING",Long.toString(orderId));
            s.db.update("UPDATE orders SET status='COMPLETED',shares=?,fee=?,net_amount=?,nav=?,planned_confirmation_date=?,confirmation_date=?,confirmed_at=?,rounding_difference=? WHERE id=?",shares,fee,money(net.add(interest)),nav,end,actualDay,s.now(),net.add(interest).subtract(shares.multiply(nav)).setScale(12,RoundingMode.HALF_UP),orderId);
            s.db.update("UPDATE offering_orders SET status='COMPLETED',interest=? WHERE order_id=?",interest,orderId);
        } else {
            s.trades.payout(id(order,"userId"),id(order,"tradingAccountId"),id(order,"sourceCardId"),"OFFERING_REFUND",Long.toString(orderId),value.add(interest),actualDay);
            s.db.update("UPDATE orders SET status='CONFIRMED',net_amount=?,reason='募集失败，等待原路退款',planned_confirmation_date=?,confirmation_date=?,confirmed_at=?,payment_date=? WHERE id=?",value.add(interest),end,actualDay,s.now(),actualDay,orderId);
            s.db.update("UPDATE offering_orders SET status='REFUND_PENDING',interest=? WHERE order_id=?",interest,orderId);
        }
        s.accounts.notifyUser(id(order,"userId"),"OFFERING",Long.toString(orderId),"募集结果通知",outcome.equals("ESTABLISHED")?"认购份额已确认":"募集失败，退款保持原账户来源");
        return true;
    }
}
