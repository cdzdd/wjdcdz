package com.hengsheng.fund;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class LifecycleService {
    private final AdvancedSupport s;
    private final MoneyIncomeService incomes;
    LifecycleService(AdvancedSupport support,MoneyIncomeService incomes) { s=support;this.incomes=incomes; }

    @Transactional
    Map<String,Object> terminate(long fundId,Map<String,Object> input) {
        s.accounts.requireRole("ADMIN");String reason=text(input,"reason");
        var fund=s.db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fundId);
        if("LIQUIDATED".equals(fund.get("state"))) throw ApiException.conflict("基金已完成清盘分配");
        if(s.db.scalar("SELECT COUNT(*) FROM orders WHERE fund_class_id=? AND status='SUBMITTED'",fundId).signum()>0
                || s.db.scalar("SELECT COUNT(*) FROM conversions WHERE status='SUBMITTED' AND (source_class_id=? OR target_class_id=?)",fundId,fundId).signum()>0)
            throw ApiException.conflict("还有待确认交易或转换，请先完成确认/允许期限内撤单；暂停新交易请使用申赎开关");
        if(!"TERMINATED".equals(fund.get("state")) || fund.get("terminatedAt")==null) {
            s.db.update("UPDATE fund_classes SET state='TERMINATED',purchase_open=FALSE,redemption_open=FALSE,terminated_at=?,rule_version=rule_version+1 WHERE id=?",s.now(),fundId);
            s.db.update("UPDATE sip_plans SET status='TERMINATED' WHERE fund_class_id=? AND status!='TERMINATED'",fundId);
            s.changed("TERMINATE_FUND","FUND",fundId,reason);
        }
        return s.db.one("SELECT * FROM fund_classes WHERE id=?",fundId);
    }

    @Transactional
    Map<String,Object> liquidate(Map<String,Object> input) {
        s.accounts.requireRole("ADMIN");long fid=id(input,"fundClassId");String reason=text(input,"reason");
        BigDecimal perShare=amount(input,"perShare",6);LocalDate day=date(input.get("date"));
        var fund=s.db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fid);
        var prior=s.db.rows("SELECT * FROM liquidation_events WHERE fund_class_id=? FOR UPDATE",fid);
        if(!prior.isEmpty()) {
            var event=prior.getFirst();
            if(decimal(event.get("perShare")).compareTo(perShare)!=0 || !date(event.get("date")).equals(day)) throw ApiException.conflict("清盘事件已固定，不能变更分配额或日期");
            return view(id(event,"id"));
        }
        if(!"TERMINATED".equals(fund.get("state"))) throw ApiException.conflict("请先终止基金交易");
        if(!day.equals(s.now().toLocalDate()) || day.isBefore(date(fund.get("terminatedAt")))) throw ApiException.bad("清盘日期须为当前业务日且不能早于终止日期");
        requireSettled(fid,day,"MONEY".equals(fund.get("type")));
        long event=s.db.insert("INSERT INTO liquidation_events(fund_class_id,per_share,date,reason,created_at) VALUES(?,?,?,?,?)",fid,perShare,day,reason,s.now());
        for(var account:s.db.rows("SELECT user_id,trading_account_id,SUM(shares) shares FROM position_lots WHERE fund_class_id=? AND shares>0 GROUP BY user_id,trading_account_id ORDER BY trading_account_id",fid)) {
            long uid=id(account,"userId"),tid=id(account,"tradingAccountId");
            var allocations=s.trades.reserveLots(uid,tid,fid,decimal(account.get("shares")));
            var byCard=new LinkedHashMap<Long,BigDecimal>();
            for(var allocation:allocations) byCard.merge(id(allocation,"sourceCardId"),decimal(allocation.get("shares")),BigDecimal::add);
            s.trades.consumeLots(allocations,"LIQUIDATION",Long.toString(event));
            for(var source:byCard.entrySet()) {
                BigDecimal value=money(source.getValue().multiply(perShare));
                long payout=s.trades.payout(uid,tid,source.getKey(),"LIQUIDATION",Long.toString(event),value,day);
                s.db.insert("INSERT INTO liquidation_items(event_id,user_id,trading_account_id,source_card_id,shares,amount,payout_id) VALUES(?,?,?,?,?,?,?)",event,uid,tid,source.getKey(),source.getValue(),value,payout);
            }
            s.accounts.notifyUser(uid,"LIQUIDATION",event+"-"+tid,"清盘分配已登记","份额已核销，清盘应收等待原交易账户回款");
        }
        disposeTails(fid,event,day);
        s.db.update("UPDATE liquidation_events SET status='COMPLETED' WHERE id=?",event);
        s.db.update("UPDATE fund_classes SET state='LIQUIDATED' WHERE id=?",fid);
        s.changed("LIQUIDATE_FUND","LIQUIDATION",event,reason);
        return view(event);
    }
    private void disposeTails(long fid,long event,LocalDate day) {
        for(var snapshot:s.db.rows("SELECT m.* FROM money_income_snapshots m WHERE m.fund_class_id=? AND m.status='COMPLETED' AND m.tail<>0 AND NOT EXISTS(SELECT 1 FROM money_income_snapshots newer WHERE newer.trading_account_id=m.trading_account_id AND newer.fund_class_id=m.fund_class_id AND newer.source_card_id=m.source_card_id AND newer.date>m.date) ORDER BY m.trading_account_id,m.source_card_id FOR UPDATE",fid)) {
            long sid=id(snapshot,"id"),uid=id(snapshot,"userId"),tid=id(snapshot,"tradingAccountId"),cid=id(snapshot,"sourceCardId");
            s.accounts.lockAccount(uid,tid);
            BigDecimal original=decimal(snapshot.get("tail")),settled=money(original);
            if(settled.signum()<0 && s.db.scalar("SELECT available FROM wallets WHERE trading_account_id=? FOR UPDATE",tid).compareTo(settled.negate())<0)
                throw ApiException.conflict("清盘尾差损失须由原交易账户结清，请先补足可用模拟资金");
            String reason=settled.signum()==0?"货币净值为1，尾差按现金两位HALF_UP舍入为0，保留原十二位记录":"货币收益尾差按现金两位HALF_UP在原交易账户最终结算";
            long disposal=s.db.insert("INSERT INTO money_tail_disposals(event_id,snapshot_id,user_id,trading_account_id,fund_class_id,source_card_id,original_tail,settled_amount,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",event,sid,uid,tid,fid,cid,original,settled,reason,s.now());
            if(settled.signum()>0) {
                long payout=s.trades.payout(uid,tid,cid,"MONEY_TAIL",Long.toString(disposal),settled,day);
                s.db.update("UPDATE money_tail_disposals SET payout_id=? WHERE id=?",payout,disposal);
            } else if(settled.signum()<0) {
                s.db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",settled,tid);
                s.accounts.cash(uid,tid,"MONEY_TAIL",Long.toString(disposal),"LOSS_DEBIT",settled,BigDecimal.ZERO);
            }
            s.db.update("UPDATE money_income_snapshots SET tail=0,reason=? WHERE id=?","清盘尾差处置记录 "+disposal,sid);
            s.changed("DISPOSE_MONEY_TAIL","MONEY_TAIL",disposal,"原尾差="+original.toPlainString()+"；结算="+settled.toPlainString()+"；"+reason);
        }
    }
    private void requireSettled(long fid,LocalDate day,boolean moneyFund) {
        if(s.db.scalar("SELECT COUNT(*) FROM orders WHERE fund_class_id=? AND status IN ('SUBMITTED','CONFIRMED')",fid).signum()>0
                || s.db.scalar("SELECT COUNT(*) FROM conversions WHERE (source_class_id=? OR target_class_id=?) AND status='SUBMITTED'",fid,fid).signum()>0
                || s.db.scalar("SELECT COUNT(*) FROM position_lots WHERE fund_class_id=? AND frozen_shares>0",fid).signum()>0)
            throw ApiException.conflict("请先处置未确认交易与冻结份额");
        if(s.db.scalar("""
            SELECT COUNT(*) FROM payouts p WHERE p.status IN('PENDING','BLOCKED') AND
            ((p.source_type IN ('REDEMPTION','OFFERING_REFUND') AND EXISTS
                (SELECT 1 FROM orders o WHERE o.id=CAST(p.source_id AS UNSIGNED) AND o.fund_class_id=?))
              OR (p.source_type='DIVIDEND' AND EXISTS
                (SELECT 1 FROM dividend_entitlements e JOIN dividend_events d ON d.id=e.event_id WHERE e.id=CAST(p.source_id AS UNSIGNED) AND d.fund_class_id=?)))
            """,fid,fid).signum()>0) throw ApiException.conflict("请先完成原基金的应付资金");
        if(s.db.scalar("SELECT COUNT(*) FROM dividend_events WHERE fund_class_id=? AND status!='COMPLETED'",fid).signum()>0
                || s.db.scalar("SELECT COUNT(*) FROM dividend_entitlements e JOIN dividend_events d ON d.id=e.event_id WHERE d.fund_class_id=? AND e.status!='COMPLETED'",fid).signum()>0)
            throw ApiException.conflict("请先完成未结分红");
        if(moneyFund && incomes.unsettled(fid,day.minusDays(1))) throw ApiException.conflict("请先补齐并结转清盘前的自然日收益");
    }
    private Map<String,Object> view(long id) {
        var result=new LinkedHashMap<>(s.db.one("SELECT * FROM liquidation_events WHERE id=?",id));
        result.put("items",s.db.rows("SELECT i.*,p.status payment_status FROM liquidation_items i JOIN payouts p ON p.id=i.payout_id WHERE i.event_id=? ORDER BY i.trading_account_id,i.source_card_id",id));
        return result;
    }
}
