package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class DividendService {
    private final AdvancedSupport s;
    DividendService(AdvancedSupport s) { this.s=s; }
    @Transactional
    Map<String,Object> create(Map<String,Object> input) {
        long fid=id(input,"fundClassId");
        var fund=s.db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fid);
        if("MONEY".equals(fund.get("type")) || !"OPEN".equals(fund.get("state"))) throw new IllegalArgumentException("该产品不支持普通分红");
        LocalDate record=date(input.get("recordDate")),ex=date(input.get("exDate")),pay=date(input.get("paymentDate"));
        if(!ex.isAfter(record) || pay.isBefore(ex)) throw new IllegalArgumentException("除息日须晚于登记日，支付日不早于除息日");
        if(record.isBefore(s.now().toLocalDate()) || (record.equals(s.now().toLocalDate()) && !s.now().toLocalTime().isBefore(java.time.LocalTime.parse(fund.get("cutoffTime").toString()))))
            throw ApiException.conflict("请在权益登记日截止前创建分红事件，历史分红不能普通补录");
        if(!s.db.rows("SELECT id FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",fid,ex).isEmpty())
            throw ApiException.conflict("除息日净值已经发布，不能再追加改变该日资产的分红");
        BigDecimal value=amount(input,"perShare",6);
        var old=s.db.rows("SELECT id FROM dividend_events WHERE fund_class_id=? AND record_date=? AND ex_date=?",fid,record,ex);
        if(!old.isEmpty()) throw new IllegalArgumentException("同一分红事件已登记");
        long id=s.db.insert("INSERT INTO dividend_events(fund_class_id,record_date,ex_date,payment_date,per_share,created_at) VALUES(?,?,?,?,?,?)",fid,record,ex,pay,value,s.now());
        s.changed("CREATE_DIVIDEND","DIVIDEND",id,"登记模拟分红");return s.db.one("SELECT * FROM dividend_events WHERE id=?",id);
    }
    @Transactional
    Map<String,Object> preference(long userId,long fundId,String mode) {
        if(!Set.of("CASH","REINVEST").contains(mode)) throw new IllegalArgumentException("分红方式无效");
        var fund=s.db.one("SELECT id,cutoff_time FROM fund_classes WHERE id=?",fundId);
        for(var event:s.db.rows("SELECT record_date FROM dividend_events WHERE fund_class_id=? AND status='DRAFT'",fundId)) {
            if(!s.now().isBefore(date(event.get("recordDate")).atTime(java.time.LocalTime.parse(fund.get("cutoffTime").toString()))))
                throw new IllegalArgumentException("本期分红方式已截止，请待权益快照登记后修改下期方式");
        }
        s.db.update("INSERT INTO dividend_preferences(user_id,fund_class_id,mode) VALUES(?,?,?) ON DUPLICATE KEY UPDATE mode=VALUES(mode)",userId,fundId,mode);
        return Map.of("fundClassId",fundId,"mode",mode);
    }
    @Transactional
    Map<String,Object> snapshot(long eventId) {
        var event=s.db.one("SELECT * FROM dividend_events WHERE id=? FOR UPDATE",eventId);
        if(!"DRAFT".equals(event.get("status"))) return Map.of("processed",0,"waiting",0,"failed",0);
        LocalDate record=date(event.get("recordDate"));
        if(s.now().isBefore(record.plusDays(1).atStartOfDay())) throw new IllegalArgumentException("权益登记日尚未结束");
        if(s.db.scalar("SELECT COUNT(*) FROM orders WHERE fund_class_id=? AND type!='OFFERING' AND status='SUBMITTED' AND confirmation_date<=?",event.get("fundClassId"),record).signum()>0)
            throw new IllegalArgumentException("登记日前应确认订单尚未完成，请先处理交易");
        var lots=eligibleLots(id(event,"fundClassId"),record);
        for(long aid:lots.stream().map(l->id(l,"tradingAccountId")).distinct().sorted().toList()){
            var owner=lots.stream().filter(l->id(l,"tradingAccountId")==aid).findFirst().orElseThrow();s.accounts.lockAccount(id(owner,"userId"),aid);
        }
        lots=eligibleLots(id(event,"fundClassId"),record);
        for(var lot:lots) {
            BigDecimal shares=decimal(lot.get("eligibleShares"));
            BigDecimal value=AdvancedAmounts.dividend(shares,decimal(event.get("perShare")));
            var preference=s.db.rows("SELECT mode FROM dividend_preferences WHERE user_id=? AND fund_class_id=?",lot.get("userId"),event.get("fundClassId"));
            String mode=preference.isEmpty()?"CASH":preference.getFirst().get("mode").toString();
            var existing=s.db.rows("SELECT id FROM dividend_entitlements WHERE event_id=? AND trading_account_id=? AND source_card_id=?",eventId,lot.get("tradingAccountId"),lot.get("sourceCardId"));
            long eid=existing.isEmpty()?s.db.insert("INSERT INTO dividend_entitlements(event_id,user_id,trading_account_id,source_card_id,fund_account_id,shares,amount,mode,created_at) VALUES(?,?,?,?,?,0,0,?,?)",eventId,lot.get("userId"),lot.get("tradingAccountId"),lot.get("sourceCardId"),lot.get("fundAccountId"),mode,s.now()):id(existing.getFirst(),"id");
            s.db.update("INSERT INTO dividend_lot_entitlements(entitlement_id,lot_id,shares,amount) VALUES(?,?,?,?)",eid,lot.get("lotId"),shares,value);
            s.db.update("UPDATE dividend_entitlements SET shares=shares+?,amount=amount+? WHERE id=?",shares,value,eid);
        }
        for(var right:s.db.rows("SELECT * FROM dividend_entitlements WHERE event_id=?",eventId))
            s.db.insert("INSERT INTO extension_replay_inputs(source_type,source_id,trading_account_id,phase,effective_date,applied_at,operation_sequence,payload) SELECT 'DIVIDEND',?,?,'REGISTER',?,?,COALESCE(MAX(id),0)+1,JSON_OBJECT('recordDate',?,'exDate',?,'paymentDate',?,'perShare',?,'mode',?,'fundClassId',?) FROM share_entries",right.get("id"),right.get("tradingAccountId"),record,s.now(),record,event.get("exDate"),event.get("paymentDate"),event.get("perShare"),right.get("mode"),event.get("fundClassId"));
        s.db.update("UPDATE dividend_events SET status='SNAPSHOTTED' WHERE id=?",eventId);
        s.changed("SNAPSHOT_DIVIDEND","DIVIDEND",eventId,"按交易账户和历史来源登记权益");
        return Map.of("processed",lots.size(),"waiting",0,"failed",0);
    }
    public boolean hasUnregisteredRightsForAccount(long accountId) {
        for(var event:s.db.rows("SELECT DISTINCT d.fund_class_id,d.record_date FROM dividend_events d JOIN position_lots l ON l.fund_class_id=d.fund_class_id WHERE l.trading_account_id=? AND d.status='DRAFT' AND d.record_date<=?",accountId,s.now().toLocalDate()))
            if(eligibleLots(id(event,"fundClassId"),date(event.get("recordDate"))).stream().anyMatch(lot->id(lot,"tradingAccountId")==accountId)) return true;
        return false;
    }
    private List<Map<String,Object>> eligibleLots(long fundId,LocalDate record) {
        return s.db.rows("""
            SELECT l.id lot_id,l.user_id,l.trading_account_id,l.source_card_id,l.fund_account_id,SUM(e.shares_delta) eligible_shares
            FROM position_lots l JOIN share_entries e ON e.lot_id=l.id
            LEFT JOIN orders o ON e.source_type='REDEMPTION' AND o.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN conversions c ON e.source_type='CONVERSION' AND c.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN liquidation_events q ON e.source_type='LIQUIDATION' AND q.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN money_income_snapshots m ON e.source_type='MONEY_INCOME' AND m.id=CAST(e.source_id AS UNSIGNED)
            WHERE l.fund_class_id=? AND e.shares_delta!=0 AND
              CASE WHEN e.effective_date IS NOT NULL THEN e.effective_date WHEN e.shares_delta>0 THEN l.acquired_date
                WHEN e.source_type='REDEMPTION' THEN COALESCE(o.confirmation_date,DATE(e.created_at))
                WHEN e.source_type='CONVERSION' THEN COALESCE(c.confirmation_date,DATE(e.created_at))
                WHEN e.source_type='LIQUIDATION' THEN COALESCE(q.date,DATE(e.created_at))
                WHEN e.source_type='MONEY_INCOME' THEN COALESCE(m.date,DATE(e.created_at))
                ELSE DATE(e.created_at) END <= ?
            GROUP BY l.id,l.user_id,l.trading_account_id,l.source_card_id,l.fund_account_id HAVING SUM(e.shares_delta)>0
            """,fundId,record);
    }
    Map<String,Object> execute(long eventId) {
        var event=s.db.one("SELECT * FROM dividend_events WHERE id=?",eventId);
        if("DRAFT".equals(event.get("status"))) throw new IllegalArgumentException("请先登记权益快照");
        int done=0,wait=0,failed=0;var details=new java.util.ArrayList<String>();var waitingIds=new java.util.ArrayList<Long>();
        long cursor=0;while(true){var page=s.db.rows("SELECT id FROM dividend_entitlements WHERE event_id=? AND status='PENDING' AND id>? ORDER BY id LIMIT 200",eventId,cursor);if(page.isEmpty())break;
        for(var row:page) {cursor=id(row,"id");
            try {
                if(s.tx(()->executeOne(id(row,"id"),event))) done++;
                else if("PENDING".equals(s.db.one("SELECT status FROM dividend_entitlements WHERE id=?",row.get("id")).get("status"))) {
                    wait++;waitingIds.add(id(row,"id"));
                }
            }
            catch(RuntimeException ex) { failed++;details.add("分红权益"+row.get("id")+"："+ex.getMessage()); }
        }}
        if(wait==0 && failed==0) s.db.update("UPDATE dividend_events SET status='COMPLETED' WHERE id=?",eventId);
        return Map.of("processed",done,"waiting",wait,"failed",failed,"details",details,"waitingIds",waitingIds);
    }
    private boolean executeOne(long eid,Map<String,Object> event) {
        var owner=s.db.one("SELECT user_id,trading_account_id FROM dividend_entitlements WHERE id=?",eid);
        s.accounts.lockAccount(id(owner,"userId"),id(owner,"tradingAccountId"));
        var right=s.db.one("SELECT * FROM dividend_entitlements WHERE id=? FOR UPDATE",eid);
        if(!"PENDING".equals(right.get("status"))) return false;
        LocalDate ex=date(event.get("exDate"));
        if(ex.isAfter(s.now().toLocalDate())) return false;
        var navRows=s.db.rows("SELECT nav FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",event.get("fundClassId"),ex);
        if(navRows.isEmpty()) return false;
        if(!yes(right.get("costAdjusted"))) {
            for(var item:s.db.rows("SELECT * FROM dividend_lot_entitlements WHERE entitlement_id=? ORDER BY lot_id",eid)) {
                var lot=s.db.one("SELECT * FROM position_lots WHERE id=? FOR UPDATE",item.get("lotId"));
                BigDecimal eligible=decimal(item.get("shares")),remaining=decimal(lot.get("shares"));
                BigDecimal reduction=eligible.signum()==0?BigDecimal.ZERO:money(decimal(item.get("amount")).multiply(remaining.min(eligible)).divide(eligible,12,RoundingMode.HALF_UP));
                s.db.update("UPDATE position_lots SET cost=cost-? WHERE id=?",reduction,item.get("lotId"));
                s.db.update("INSERT INTO share_entries(user_id,trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,shares_delta,frozen_delta,cost_delta,created_at,effective_date) VALUES(?,?,?,?,'DIVIDEND',?,'COST_ADJUST',0,0,?,?,?)",right.get("userId"),right.get("tradingAccountId"),event.get("fundClassId"),item.get("lotId"),Long.toString(eid),reduction.negate(),s.now(),ex);
            }
            s.db.update("UPDATE dividend_entitlements SET cost_adjusted=TRUE WHERE id=?",eid);
        }
        if(!s.due(date(event.get("paymentDate")),9)) return false;
        BigDecimal value=decimal(right.get("amount"));
        if("CASH".equals(right.get("mode"))) {
            if(right.get("payoutId")==null) {
                long payout=s.trades.payout(id(right,"userId"),id(right,"tradingAccountId"),id(right,"sourceCardId"),"DIVIDEND",Long.toString(eid),value,date(event.get("paymentDate")));
                s.db.update("UPDATE dividend_entitlements SET payout_id=? WHERE id=?",payout,eid);
            }
            return false;
        }
        BigDecimal nav=decimal(navRows.getFirst().get("nav"));
        BigDecimal shares=value.divide(nav,4,RoundingMode.HALF_UP);
        s.trades.addLot(id(right,"userId"),id(right,"tradingAccountId"),id(event,"fundClassId"),id(right,"sourceCardId"),id(right,"fundAccountId"),shares,value,ex,"DIVIDEND",Long.toString(eid));
        s.db.update("UPDATE dividend_entitlements SET status='COMPLETED' WHERE id=?",eid);
        s.db.update("UPDATE nav_records SET used=TRUE WHERE fund_class_id=? AND date=?",event.get("fundClassId"),ex);
        s.accounts.notifyUser(id(right,"userId"),"DIVIDEND",Long.toString(eid),"分红已登记","红利再投资份额已进入原交易账户");return true;
    }
}
