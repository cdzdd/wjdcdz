package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class ConversionService {
    private final AdvancedSupport s;
    ConversionService(AdvancedSupport s) { this.s=s; }
    Map<String,Object> quote(long userId,Map<String,Object> input) {
        long aid=id(input,"tradingAccountId"),source=id(input,"sourceClassId"),target=id(input,"targetClassId");
        BigDecimal requested=amount(input,"shares",4);s.account(userId,aid,false);
        var fund=s.db.one("SELECT * FROM fund_classes WHERE id=?",source);
        var other=s.db.one("SELECT * FROM fund_classes WHERE id=?",target);
        if(s.db.rows("SELECT * FROM conversion_pairs WHERE source_class_id=? AND target_class_id=? AND enabled=TRUE",source,target).isEmpty()) throw new IllegalArgumentException("该组合不支持转换");
        var from=s.db.one("SELECT * FROM nav_records WHERE fund_class_id=? AND published=TRUE AND date<=? ORDER BY date DESC LIMIT 1",source,s.now().toLocalDate());
        var to=s.db.one("SELECT * FROM nav_records WHERE fund_class_id=? AND published=TRUE AND date<=? ORDER BY date DESC LIMIT 1",target,s.now().toLocalDate());
        BigDecimal sourceNav=decimal(from.get("nav")),targetNav=decimal(to.get("nav")),left=requested,redeemFee=BigDecimal.ZERO;
        for(var lot:s.db.rows("SELECT * FROM position_lots WHERE user_id=? AND trading_account_id=? AND fund_class_id=? AND shares>frozen_shares ORDER BY acquired_date,id",userId,aid,source)) {
            BigDecimal take=left.min(decimal(lot.get("shares")).subtract(decimal(lot.get("frozenShares"))));
            redeemFee=redeemFee.add(FundMath.redemptionFee(take,sourceNav,ChronoUnit.DAYS.between(date(lot.get("acquiredDate")),s.now().toLocalDate()),decimal(fund.get("redemptionRate"))));
            left=left.subtract(take);if(left.signum()==0) break;
        }
        if(left.signum()>0) throw new IllegalArgumentException("所选交易账户可用份额不足");
        BigDecimal gross=money(requested.multiply(sourceNav));
        BigDecimal supplement=AdvancedAmounts.supplement(gross.subtract(redeemFee),decimal(fund.get("purchaseRate")),decimal(other.get("purchaseRate")));
        Map<String,Object> result=new java.util.LinkedHashMap<>();
        result.put("estimated",true);result.put("sourceNav",sourceNav.toPlainString());result.put("targetNav",targetNav.toPlainString());
        result.put("sourceNavDate",from.get("date"));result.put("targetNavDate",to.get("date"));result.put("redemptionFee",redeemFee.toPlainString());
        result.put("supplementFee",supplement.toPlainString());result.put("totalFee",redeemFee.add(supplement).toPlainString());
        result.put("targetShares",gross.subtract(redeemFee).subtract(supplement).divide(targetNav,4,RoundingMode.HALF_UP).toPlainString());return result;
    }
    @Transactional
    Map<String,Object> submit(long userId,Map<String,Object> input) {
        long aid=id(input,"tradingAccountId"),source=id(input,"sourceClassId"),target=id(input,"targetClassId");
        BigDecimal shares=amount(input,"shares",4);String key=text(input,"requestKey");
        var old=s.db.rows("SELECT * FROM conversions WHERE user_id=? AND request_key=?",userId,key);
        if(!old.isEmpty()) {
            var prior=old.getFirst();
            if(id(prior,"tradingAccountId")!=aid || id(prior,"sourceClassId")!=source || id(prior,"targetClassId")!=target || shares.compareTo(decimal(prior.get("shares")))!=0)
                throw new IllegalArgumentException("请求键已用于另一笔转换");
            return prior;
        }
        s.db.rows("SELECT id FROM fund_classes WHERE id IN (?,?) ORDER BY id FOR UPDATE",source,target);
        s.account(userId,aid,false);
        var from=s.db.one("SELECT * FROM fund_classes WHERE id=?",source);
        var to=s.eligible(userId,aid,target,"OPEN",false);
        if(source==target || "MONEY".equals(from.get("type")) || "MONEY".equals(to.get("type"))
                || !from.get("manager").equals(to.get("manager")) || !"OPEN".equals(from.get("state")) || !yes(from.get("redemptionOpen"))
                || s.db.rows("SELECT * FROM conversion_pairs WHERE source_class_id=? AND target_class_id=? AND enabled=TRUE",source,target).isEmpty())
            throw new IllegalArgumentException("不支持该基金转换组合");
        if(shares.compareTo(decimal(from.get("minRedemption")))<0) throw new IllegalArgumentException("低于最低转换份额");
        LocalDate day=s.openDate(s.now().toLocalDate());
        if(day.equals(s.now().toLocalDate()) && !s.now().toLocalTime().isBefore(LocalTime.parse(from.get("cutoffTime").toString()))) day=s.nextOpen(day,1);
        LocalDate confirm=s.nextOpen(day,Math.toIntExact(id(from,"confirmationDays")));
        var allocations=s.trades.reserveLots(userId,aid,source,shares);
        long cid=s.db.insert("INSERT INTO conversions(user_id,trading_account_id,source_class_id,target_class_id,shares,application_date,confirmation_date,planned_confirmation_date,cutoff_time,source_rate,target_rate,redemption_rate,source_rule_version,target_rule_version,request_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",userId,aid,source,target,shares,day,confirm,confirm,from.get("cutoffTime"),from.get("purchaseRate"),to.get("purchaseRate"),from.get("redemptionRate"),from.get("ruleVersion"),to.get("ruleVersion"),key,s.now());
        for(var row:allocations) {
            s.db.insert("INSERT INTO conversion_allocations(conversion_id,lot_id,shares,cost,source_card_id,fund_account_id,acquired_date) VALUES(?,?,?,?,?,?,?)",cid,row.get("lotId"),row.get("shares"),row.get("cost"),row.get("sourceCardId"),row.get("fundAccountId"),row.get("acquiredDate"));
            s.trades.shareEntry(userId,aid,source,id(row,"lotId"),"CONVERSION",Long.toString(cid),"RESERVE",BigDecimal.ZERO,decimal(row.get("shares")),BigDecimal.ZERO);
        }
        s.accounts.linkFundAccount(userId,aid,target);
        s.changed("SUBMIT_CONVERSION","CONVERSION",cid,"同交易账户保留来源转换");
        return s.db.one("SELECT * FROM conversions WHERE id=?",cid);
    }
    private List<Map<String,Object>> allocations(long id) {
        return s.db.rows("SELECT lot_id,shares,cost,source_card_id,fund_account_id,acquired_date FROM conversion_allocations WHERE conversion_id=? ORDER BY lot_id",id);
    }
    @Transactional
    Map<String,Object> cancel(long userId,long cid) {
        var conversion=s.db.one("SELECT * FROM conversions WHERE id=? AND user_id=? FOR UPDATE",cid,userId);
        if("CANCELLED".equals(conversion.get("status"))) return conversion;
        if(!"SUBMITTED".equals(conversion.get("status")) || !s.now().isBefore(date(conversion.get("applicationDate")).atTime(LocalTime.parse(conversion.get("cutoffTime").toString()))))
            throw new IllegalArgumentException("转换已过可撤期限或已确认");
        var lots=allocations(cid);s.trades.releaseLots(lots);
        for(var lot:lots) s.trades.shareEntry(userId,id(conversion,"tradingAccountId"),id(conversion,"sourceClassId"),id(lot,"lotId"),"CONVERSION",Long.toString(cid),"RELEASE",BigDecimal.ZERO,decimal(lot.get("shares")).negate(),BigDecimal.ZERO);
        s.db.update("UPDATE conversions SET status='CANCELLED' WHERE id=?",cid);
        s.changed("CANCEL_CONVERSION","CONVERSION",cid,"用户撤单");return s.db.one("SELECT * FROM conversions WHERE id=?",cid);
    }
    Map<String,Object> run(LocalDate date) {
        int done=0,waiting=0;
        for(var row:s.db.rows("SELECT id FROM conversions WHERE status='SUBMITTED' AND confirmation_date<=? ORDER BY id",date)) {
            if(s.tx(()->confirm(id(row,"id")))) done++; else waiting++;
        }
        return Map.of("processed",done,"waiting",waiting,"failed",0);
    }
    private boolean confirm(long id) {
        var context=s.db.one("SELECT source_class_id,target_class_id FROM conversions WHERE id=?",id);
        s.db.rows("SELECT id FROM fund_classes WHERE id IN (?,?) ORDER BY id FOR UPDATE",context.get("sourceClassId"),context.get("targetClassId"));
        var row=s.db.one("SELECT * FROM conversions WHERE id=? FOR UPDATE",id);
        if(!"SUBMITTED".equals(row.get("status"))) return false;
        if(!s.due(date(row.get("confirmationDate")),9)) return false;
        var from=s.db.rows("SELECT nav FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",row.get("sourceClassId"),row.get("applicationDate"));
        var to=s.db.rows("SELECT nav FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",row.get("targetClassId"),row.get("applicationDate"));
        if(from.isEmpty() || to.isEmpty()) {
            s.db.update("UPDATE conversions SET reason='等待双方所属交易日净值' WHERE id=?",id);return false;
        }
        BigDecimal sourceNav=decimal(from.getFirst().get("nav")),targetNav=decimal(to.getFirst().get("nav"));
        BigDecimal totalShares=BigDecimal.ZERO,totalFees=BigDecimal.ZERO;
        var lots=allocations(id);
        long fa=s.accounts.linkFundAccount(id(row,"userId"),id(row,"tradingAccountId"),id(row,"targetClassId"));
        s.trades.consumeLots(lots,"CONVERSION",Long.toString(id));
        for(var lot:lots) s.db.update("UPDATE conversion_allocations SET cost=? WHERE conversion_id=? AND lot_id=?",lot.get("cost"),id,lot.get("lotId"));
        for(var lot:lots) {
            BigDecimal units=decimal(lot.get("shares"));
            BigDecimal gross=units.multiply(sourceNav);
            long days=ChronoUnit.DAYS.between(date(lot.get("acquiredDate")),date(row.get("applicationDate")));
            BigDecimal redeemFee=FundMath.redemptionFee(units,sourceNav,days,decimal(row.get("redemptionRate")));
            BigDecimal supplement=AdvancedAmounts.supplement(money(gross.subtract(redeemFee)),decimal(row.get("sourceRate")),decimal(row.get("targetRate")));
            BigDecimal targetShares=gross.subtract(redeemFee).subtract(supplement).divide(targetNav,4,RoundingMode.HALF_UP);
            if(targetShares.signum()<=0) throw new IllegalArgumentException("转换后份额不足");
            s.trades.addLot(id(row,"userId"),id(row,"tradingAccountId"),id(row,"targetClassId"),id(lot,"sourceCardId"),fa,targetShares,decimal(lot.get("cost")),s.now().toLocalDate(),"CONVERSION",id+"-"+lot.get("lotId"));
            totalShares=totalShares.add(targetShares);totalFees=totalFees.add(redeemFee).add(supplement);
        }
        s.db.update("UPDATE conversions SET status='COMPLETED',source_nav=?,target_nav=?,target_shares=?,fee=?,reason=NULL,confirmation_date=?,confirmed_at=? WHERE id=?",sourceNav,targetNav,totalShares,totalFees,s.now().toLocalDate(),s.now(),id);
        s.db.update("UPDATE nav_records SET used=TRUE WHERE date=? AND fund_class_id IN (?,?)",row.get("applicationDate"),row.get("sourceClassId"),row.get("targetClassId"));
        s.accounts.notifyUser(id(row,"userId"),"CONVERSION",Long.toString(id),"转换已确认","来源账户保持不变，目标份额已登记");return true;
    }
}
