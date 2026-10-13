package com.hengsheng.fund;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
public class MoneyIncomeService {
    private final AdvancedSupport s;
    MoneyIncomeService(AdvancedSupport support) { s=support; }

    @Transactional
    Map<String,Object> write(Map<String,Object> input) {
        s.accounts.requireRole("OPERATOR");
        long fid=id(input,"fundClassId");
        var fund=s.db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fid);
        if(!"MONEY".equals(fund.get("type"))) throw ApiException.bad("仅货币基金可登记万份收益");
        LocalDate day=date(input.get("date"));
        if(day.isAfter(s.now().toLocalDate())) throw ApiException.bad("不能发布未来日期的收益");
        BigDecimal income=signed(input.get("incomePerTenThousand"),20,8);
        BigDecimal annual=signed(input.getOrDefault("annualYield","0"),12,8);
        var prior=s.db.rows("SELECT * FROM money_income_records WHERE fund_class_id=? AND date=? FOR UPDATE",fid,day);
        if(!prior.isEmpty() && yes(prior.getFirst().get("used"))) throw ApiException.conflict("收益已用于快照，不可修改");
        s.db.update("INSERT INTO money_income_records(fund_class_id,date,income_per_ten_thousand,annual_yield) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE income_per_ten_thousand=VALUES(income_per_ten_thousand),annual_yield=VALUES(annual_yield)",fid,day,income,annual);
        var row=s.db.one("SELECT * FROM money_income_records WHERE fund_class_id=? AND date=?",fid,day);
        s.changed("WRITE_MONEY_INCOME","MONEY_INCOME",id(row,"id"),"发布模拟万份收益；七日年化仅展示");
        return row;
    }
    private BigDecimal signed(Object input,int precision,int scale) {
        BigDecimal value;
        try { value=new BigDecimal(String.valueOf(input)); } catch(NumberFormatException ex) { throw ApiException.bad("收益数字格式无效"); }
        if(value.scale()>scale || value.precision()-value.scale()>precision-scale) throw ApiException.bad("收益精度超出范围");
        return value.setScale(scale);
    }

    Map<String,Object> run(LocalDate through) {
        int processed=0,waiting=0,failed=0;
        var details=new ArrayList<String>();
        for(var fund:s.db.rows("SELECT f.id,MIN(l.acquired_date) first_date FROM fund_classes f JOIN position_lots l ON l.fund_class_id=f.id WHERE f.type='MONEY' AND f.state!='LIQUIDATED' GROUP BY f.id ORDER BY f.id")) {
            long fid=id(fund,"id");
            for(LocalDate day=date(fund.get("firstDate")).plusDays(1);!day.isAfter(through);day=day.plusDays(1)) {
                if(s.now().isBefore(day.plusDays(1).atTime(0,10))) { waiting++; break; }
                LocalDate current=day;
                try {
                    var result=processDay(fid,current,details);
                    processed+=result[0];waiting+=result[1];failed+=result[3];
                    if(result[2]==0) break;
                } catch(Exception ex) { failed++;details.add(fid+"/"+day+"："+ex.getMessage());break; }
            }
        }
        return Map.of("processed",processed,"waiting",waiting,"failed",failed,"details",details);
    }

    private int[] processDay(long fid,LocalDate day,List<String> details) {
        var groups=eligible(fid,day);
        if(groups.isEmpty()) return new int[]{0,0,1,0};
        if(s.db.rows("SELECT id FROM money_income_records WHERE fund_class_id=? AND date=?",fid,day).isEmpty()) return new int[]{0,1,0,0};
        int processed=0,waiting=0,failed=0;
        for(var group:groups) {
            try{var result=s.tx(()->processGroup(fid,day,group));processed+=result[0];waiting+=result[1];}
            catch(RuntimeException ex){failed++;details.add(fid+"/"+day+"/账户"+group.get("tradingAccountId")+"："+ex.getMessage());}
        }
        return new int[]{processed,waiting,failed==0?1:0,failed};
    }
    private int[] processGroup(long fid,LocalDate day,Map<String,Object> candidate){
        s.accounts.lockAccount(id(candidate,"userId"),id(candidate,"tradingAccountId"));
        s.db.one("SELECT id FROM fund_classes WHERE id=? FOR UPDATE",fid);
        var current=eligible(fid,day,id(candidate,"tradingAccountId"),id(candidate,"sourceCardId"));if(current.isEmpty())return new int[]{0,0};var group=current.getFirst();
        var rates=s.db.rows("SELECT * FROM money_income_records WHERE fund_class_id=? AND date=? FOR UPDATE",fid,day);if(rates.isEmpty())return new int[]{0,1};BigDecimal rate=decimal(rates.getFirst().get("incomePerTenThousand"));
        if(s.db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE trading_account_id=? AND fund_class_id=? AND source_card_id=? AND date<? AND status='PENDING'",group.get("tradingAccountId"),fid,group.get("sourceCardId"),day).signum()>0)return new int[]{0,1};
        var old=s.db.rows("SELECT * FROM money_income_snapshots WHERE date=? AND trading_account_id=? AND fund_class_id=? AND source_card_id=? FOR UPDATE",day,group.get("tradingAccountId"),fid,group.get("sourceCardId"));Map<String,Object> snapshot;
        if(old.isEmpty()){
            BigDecimal qualified=decimal(group.get("eligibleShares"));long sid=s.db.insert("INSERT INTO money_income_snapshots(date,user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,income) VALUES(?,?,?,?,?,?,?,?)",day,group.get("userId"),group.get("tradingAccountId"),fid,group.get("sourceCardId"),group.get("fundAccountId"),qualified,AdvancedAmounts.moneyShares(qualified,rate));snapshot=s.db.one("SELECT * FROM money_income_snapshots WHERE id=?",sid);
            s.db.insert("INSERT INTO extension_replay_inputs(source_type,source_id,trading_account_id,phase,effective_date,applied_at,operation_sequence,payload) SELECT 'MONEY_INCOME',?,?,'SNAPSHOT',?,?,COALESCE(MAX(id),0)+1,JSON_OBJECT('rate',?,'date',?) FROM share_entries",sid,snapshot.get("tradingAccountId"),day,s.now(),rate,day);
        }else snapshot=old.getFirst();
        if("COMPLETED".equals(snapshot.get("status")))return new int[]{0,0};s.db.update("UPDATE money_income_records SET used=TRUE WHERE id=?",rates.getFirst().get("id"));
        if(!apply(snapshot,day))return new int[]{0,1};
        s.db.insert("INSERT INTO extension_replay_inputs(source_type,source_id,trading_account_id,phase,effective_date,applied_at,operation_sequence,payload) SELECT 'MONEY_INCOME',?,?,'APPLY',?,?,COALESCE(MIN(CASE WHEN source_type='MONEY_INCOME' AND source_id=? THEN id END),MAX(id)+1,1),JSON_OBJECT('rate',?,'date',?) FROM share_entries",snapshot.get("id"),snapshot.get("tradingAccountId"),day,s.now(),snapshot.get("id").toString(),rate,day);
        return new int[]{1,0};
    }

    // Positive entries use their acquired date; confirmed removals stop eligibility the next day.
    List<Map<String,Object>> eligible(long fid,LocalDate day) {
        return eligible(fid,day,0,0);
    }
    private List<Map<String,Object>> eligible(long fid,LocalDate day,long aid,long cid) {
        return s.db.rows("""
            SELECT l.user_id,l.trading_account_id,l.source_card_id,l.fund_account_id,SUM(e.shares_delta) eligible_shares
            FROM position_lots l JOIN share_entries e ON e.lot_id=l.id
            LEFT JOIN orders o ON e.source_type='REDEMPTION' AND o.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN conversions c ON e.source_type='CONVERSION' AND c.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN liquidation_events q ON e.source_type='LIQUIDATION' AND q.id=CAST(e.source_id AS UNSIGNED)
            LEFT JOIN money_income_snapshots m ON e.source_type='MONEY_INCOME' AND m.id=CAST(e.source_id AS UNSIGNED)
            WHERE l.fund_class_id=? AND (?=0 OR l.trading_account_id=?) AND (?=0 OR l.source_card_id=?) AND e.shares_delta!=0 AND
              CASE WHEN e.effective_date IS NOT NULL THEN e.effective_date WHEN e.shares_delta>0 THEN l.acquired_date
                WHEN e.source_type='REDEMPTION' THEN COALESCE(o.confirmation_date,DATE(e.created_at))
                WHEN e.source_type='CONVERSION' THEN COALESCE(c.confirmation_date,DATE(e.created_at))
                WHEN e.source_type='LIQUIDATION' THEN COALESCE(q.date,DATE(e.created_at))
                WHEN e.source_type='MONEY_INCOME' THEN COALESCE(m.date,DATE(e.created_at))
                ELSE DATE(e.created_at) END < ?
            GROUP BY l.user_id,l.trading_account_id,l.source_card_id,l.fund_account_id HAVING SUM(e.shares_delta)>0
            ORDER BY l.trading_account_id,l.source_card_id
            """,fid,aid,aid,cid,cid,day);
    }

    private boolean apply(Map<String,Object> row,LocalDate day) {
        long uid=id(row,"userId"),tid=id(row,"tradingAccountId"),fid=id(row,"fundClassId"),cid=id(row,"sourceCardId"),sid=id(row,"id");
        s.accounts.lockAccount(uid,tid);
        var previous=s.db.rows("SELECT tail FROM money_income_snapshots WHERE trading_account_id=? AND fund_class_id=? AND source_card_id=? AND date<? AND status='COMPLETED' ORDER BY date DESC LIMIT 1",tid,fid,cid,day);
        BigDecimal carried=previous.isEmpty()?BigDecimal.ZERO:decimal(previous.getFirst().get("tail"));
        BigDecimal combined=decimal(row.get("income")).add(carried);
        var current=s.db.one("SELECT COALESCE(SUM(shares),0) shares,COALESCE(SUM(frozen_shares),0) frozen_shares FROM position_lots WHERE trading_account_id=? AND fund_class_id=? AND source_card_id=?",tid,fid,cid);
        BigDecimal held=decimal(current.get("shares")),frozen=decimal(current.get("frozenShares"));
        if(held.signum()==0 && frozen.signum()==0) return applyExit(row,day,carried,combined);
        if(combined.signum()>0 && held.compareTo(frozen)==0 && s.db.scalar("SELECT COUNT(*) FROM order_lot_allocations a JOIN orders o ON o.id=a.order_id WHERE o.trading_account_id=? AND o.fund_class_id=? AND a.source_card_id=? AND o.type='REDEMPTION' AND o.status='SUBMITTED'",tid,fid,cid).signum()>0)
            return pending(sid,"末期收益等待全额赎回确认，避免产生不能赎回的小额反投份额");
        BigDecimal units=combined.setScale(4,RoundingMode.DOWN),cash=BigDecimal.ZERO;
        if(units.signum()>0) {
            s.trades.addLot(uid,tid,fid,cid,id(row,"fundAccountId"),units,BigDecimal.ZERO,day,"MONEY_INCOME",Long.toString(sid));
        } else if(units.signum()<0) {
            var lots=s.db.rows("SELECT * FROM position_lots WHERE trading_account_id=? AND fund_class_id=? AND source_card_id=? AND acquired_date<=? AND shares>0 ORDER BY acquired_date,id FOR UPDATE",tid,fid,cid,day);
            BigDecimal total=BigDecimal.ZERO,free=BigDecimal.ZERO;
            for(var lot:lots){total=total.add(decimal(lot.get("shares")));free=free.add(decimal(lot.get("shares")).subtract(decimal(lot.get("frozenShares"))));}
            BigDecimal loss=units.negate();
            if(free.compareTo(loss)<0 && total.compareTo(free)>0) return pending(sid,"负收益待处理：来源份额仍冻结，等待交易处置");
            BigDecimal consume=loss.min(free);
            cash=money(loss.subtract(consume)).negate();
            if(cash.signum()<0 && s.db.scalar("SELECT available FROM wallets WHERE trading_account_id=? FOR UPDATE",tid).compareTo(cash.negate())<0)
                return pending(sid,"负收益待处理：来源份额已赎回，等待原交易账户回款");
            BigDecimal left=consume;
            var allocations=new ArrayList<Map<String,Object>>();
            for(var lot:lots) {
                if(left.signum()==0) break;
                BigDecimal take=left.min(decimal(lot.get("shares")).subtract(decimal(lot.get("frozenShares"))));
                if(take.signum()==0) continue;
                s.db.update("UPDATE position_lots SET frozen_shares=frozen_shares+? WHERE id=?",take,lot.get("id"));
                s.trades.shareEntry(uid,tid,fid,id(lot,"id"),"MONEY_INCOME",Long.toString(sid),"RESERVE",BigDecimal.ZERO,take,BigDecimal.ZERO);
                var allocation=new LinkedHashMap<String,Object>();allocation.put("lotId",lot.get("id"));allocation.put("shares",take);allocation.put("cost",BigDecimal.ZERO);allocations.add(allocation);left=left.subtract(take);
            }
            s.trades.consumeLots(allocations,"MONEY_INCOME",Long.toString(sid));
            s.db.update("UPDATE share_entries SET effective_date=? WHERE source_type='MONEY_INCOME' AND source_id=?",day,Long.toString(sid));
            units=consume.negate();
            if(cash.signum()<0) {
                s.db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",cash,tid);
                s.accounts.cash(uid,tid,"MONEY_INCOME",Long.toString(sid),"LOSS_DEBIT",cash,BigDecimal.ZERO);
                s.db.update("UPDATE cash_entries SET effective_date=? WHERE source_type='MONEY_INCOME' AND source_id=? AND event_type='LOSS_DEBIT'",day,Long.toString(sid));
            }
        }
        BigDecimal tail=combined.subtract(units).subtract(cash).setScale(12,RoundingMode.HALF_UP);
        s.db.update("UPDATE money_income_snapshots SET applied_shares=?,cash_adjustment=?,tail=?,status='COMPLETED',reason=NULL WHERE id=?",units,cash,tail,sid);
        s.accounts.notifyUser(uid,"MONEY_INCOME",Long.toString(sid),"货币基金收益已结转",day+"收益按原交易账户与来源记账");
        return true;
    }
    private boolean pending(long id,String reason) { s.db.update("UPDATE money_income_snapshots SET reason=? WHERE id=?",reason,id);return false; }

    private boolean applyExit(Map<String,Object> row,LocalDate day,BigDecimal carried,BigDecimal combined) {
        long sid=id(row,"id"),uid=id(row,"userId"),tid=id(row,"tradingAccountId"),cid=id(row,"sourceCardId");
        BigDecimal cash=money(combined);
        if(cash.signum()<0 && s.db.scalar("SELECT available FROM wallets WHERE trading_account_id=? FOR UPDATE",tid).compareTo(cash.negate())<0)
            return pending(sid,"末期负收益等待原交易账户回款结清");
        Long payout=null;
        if(cash.signum()>0) payout=s.trades.payout(uid,tid,cid,"MONEY_EXIT_INCOME",Long.toString(sid),cash,day.plusDays(1));
        else if(cash.signum()<0) {
            s.db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",cash,tid);
            s.accounts.cash(uid,tid,"MONEY_EXIT_INCOME",Long.toString(sid),"LOSS_DEBIT",cash,BigDecimal.ZERO);
            s.db.update("UPDATE cash_entries SET effective_date=? WHERE source_type='MONEY_EXIT_INCOME' AND source_id=? AND event_type='LOSS_DEBIT'",day,Long.toString(sid));
        }
        BigDecimal original=combined.subtract(cash).setScale(12,RoundingMode.HALF_UP);
        String reason="来源份额已全部赎回，末期收益按两位HALF_UP转原账户现金，十二位尾差作为退出舍入处置保留";
        long disposal=s.db.insert("INSERT INTO money_exit_disposals(snapshot_id,user_id,trading_account_id,fund_class_id,source_card_id,carried_tail,original_tail,settled_amount,payout_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",sid,uid,tid,row.get("fundClassId"),cid,carried,original,cash,payout,reason,s.now());
        s.db.update("UPDATE money_income_snapshots SET applied_shares=0,cash_adjustment=?,tail=0,payout_id=?,status='COMPLETED',reason=? WHERE id=?",cash,payout,reason,sid);
        s.changed("DISPOSE_MONEY_EXIT","MONEY_EXIT",disposal,"末期现金="+cash.toPlainString()+"；原尾差="+original.toPlainString());
        s.accounts.notifyUser(uid,"MONEY_INCOME",Long.toString(sid),"末期货币收益已结转",cash.signum()>0?"收益将按原来源交易账户回款":"末期收益与退出舍入已在原交易账户处置");
        return true;
    }

    boolean unsettled(long fid,LocalDate through) {
        if(s.db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE fund_class_id=? AND status!='COMPLETED'",fid).signum()>0) return true;
        var first=s.db.rows("SELECT MIN(acquired_date) first_date FROM position_lots WHERE fund_class_id=?",fid);
        if(first.getFirst().get("firstDate")==null) return false;
        for(LocalDate day=date(first.getFirst().get("firstDate")).plusDays(1);!day.isAfter(through);day=day.plusDays(1))
            for(var group:eligible(fid,day))
                if(s.db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE fund_class_id=? AND date=? AND trading_account_id=? AND source_card_id=? AND status='COMPLETED'",fid,day,group.get("tradingAccountId"),group.get("sourceCardId")).signum()==0) return true;
        return false;
    }

    public boolean hasUnsettledForAccount(long tradingAccountId) {
        if(s.db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE trading_account_id=? AND status='PENDING'",tradingAccountId).signum()>0) return true;
        if(s.db.scalar("SELECT COUNT(*) FROM payouts WHERE trading_account_id=? AND source_type IN ('MONEY_EXIT_INCOME','MONEY_TAIL') AND status IN('PENDING','BLOCKED')",tradingAccountId).signum()>0) return true;
        LocalDate today=s.now().toLocalDate();
        for(var fund:s.db.rows("SELECT f.id,MIN(l.acquired_date) first_date FROM fund_classes f JOIN position_lots l ON l.fund_class_id=f.id WHERE l.trading_account_id=? AND f.type='MONEY' AND f.state!='LIQUIDATED' GROUP BY f.id",tradingAccountId)) {
            long fid=id(fund,"id");
            for(LocalDate day=date(fund.get("firstDate")).plusDays(1);!day.isAfter(today);day=day.plusDays(1))
                for(var group:eligible(fid,day))
                    if(id(group,"tradingAccountId")==tradingAccountId && s.db.scalar("SELECT COUNT(*) FROM money_income_snapshots WHERE trading_account_id=? AND fund_class_id=? AND source_card_id=? AND date=? AND status='COMPLETED'",tradingAccountId,fid,group.get("sourceCardId"),day).signum()==0) return true;
        }
        return false;
    }
}
