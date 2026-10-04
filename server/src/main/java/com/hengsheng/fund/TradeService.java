package com.hengsheng.fund;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TradeService {
    public final Db db;public final AccountService account;public final FundService funds;
    private final TransactionTemplate transactions;
    public TradeService(Db db,AccountService account,FundService funds,PlatformTransactionManager manager){this.db=db;this.account=account;this.funds=funds;transactions=new TransactionTemplate(manager);}
    public static String fingerprint(Map<String,Object> in) {
        try {var sorted=new TreeMap<String,Object>(in);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sorted.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public String orderQuery() {return "SELECT o.*,f.name fund_name,c.bank_name,c.sim_number FROM orders o JOIN fund_classes f ON f.id=o.fund_class_id JOIN bank_cards c ON c.id=o.source_card_id";}
    public Map<String,Object> order(long uid,long id){return db.one(orderQuery()+" WHERE o.user_id=? AND o.id=?",uid,id);}
    @Transactional public Map<String,Object> submit(long uid,Map<String,Object> in) {
        String type=Db.text(in,"type"),key=Db.text(in,"requestKey");if(key.length()>100)throw ApiException.bad("请求键过长");
        String fp=fingerprint(in);long fid=Db.id(in.get("fundClassId"));var f=db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",fid);db.one("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        var prior=db.rows("SELECT id,request_fingerprint FROM orders WHERE user_id=? AND request_key=? FOR UPDATE",uid,key);
        if(!prior.isEmpty()){if(!fp.equals(prior.getFirst().get("requestFingerprint")))throw ApiException.conflict("请求键已用于不同交易内容");return db.one(orderQuery()+" WHERE o.user_id=? AND o.id=? FOR UPDATE",uid,prior.getFirst().get("id"));}
        if(!Set.of("PURCHASE","REDEMPTION").contains(type))throw ApiException.bad("交易类型须为PURCHASE或REDEMPTION");
        long tid=Db.id(in.get("tradingAccountId"));var a=account.lockAccount(uid,tid);
        if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭");
        if(!"OPEN".equals(f.get("state")))throw ApiException.conflict("基金当前不开放日常交易");
        long cid=Db.id(a.get("bankCardId")),version=Db.id(a.get("bindingVersion"));
        BigDecimal amount=BigDecimal.ZERO,shares=BigDecimal.ZERO;
        if(type.equals("PURCHASE")){
            account.requireDebit(a,Db.id(in.get("bankCardId")),Db.id(in.get("bindingVersion")));funds.eligibility(uid,f);
            if(!Db.yes(f.get("purchaseOpen")))throw ApiException.conflict("基金暂停申购");
            amount=Db.positive(in,"amount",2);if(amount.compareTo(Db.decimal(f.get("minPurchase")))<0)throw ApiException.bad("未达到最低申购金额");
            if(Db.decimal(a.get("available")).compareTo(amount)<0)throw ApiException.conflict("该交易账户可用余额不足");checkQuota(tid,a,amount);
        }else {
            if(!Db.yes(f.get("redemptionOpen")))throw ApiException.conflict("基金暂停赎回");shares=Db.positive(in,"shares",4);
            BigDecimal remaining=db.scalar("SELECT COALESCE(SUM(shares-frozen_shares),0) FROM position_lots WHERE trading_account_id=? AND fund_class_id=?",tid,fid).subtract(shares);
            if(shares.compareTo(Db.decimal(f.get("minRedemption")))<0 && remaining.signum()!=0)throw ApiException.bad("未达到最低赎回份额；不足起点的剩余份额可全部赎回");
            if(remaining.signum()<0)throw ApiException.conflict("该交易账户可用份额不足");if(remaining.signum()>0&&remaining.compareTo(Db.decimal(f.get("minRedemption")))<0)throw ApiException.bad("剩余份额低于最低份额，请全额赎回");
        }
        long fa=account.linkFundAccount(uid,tid,fid);LocalDate application=funds.applicationDate(fid,account.clock()),confirmation=funds.addTradingDays(application,Math.toIntExact(Db.id(f.get("confirmationDays")))),payment=funds.addTradingDays(application,Math.toIntExact(Db.id(f.get("paymentDays"))));
        BigDecimal rate=Db.decimal(f.get("purchaseRate")),fixed=null;
        if(type.equals("PURCHASE")){var tiers=db.rows("SELECT rate,fixed_fee FROM fee_tiers WHERE fund_class_id=? AND min_amount<=? ORDER BY min_amount DESC LIMIT 1",fid,amount);if(!tiers.isEmpty()){rate=Db.decimal(tiers.getFirst().get("rate"));if(tiers.getFirst().get("fixedFee")!=null)fixed=Db.decimal(tiers.getFirst().get("fixedFee"));}}
        if(fixed!=null&&fixed.compareTo(amount)>=0)throw ApiException.bad("申购金额须大于该档位固定费用");
        long oid=db.insert("INSERT INTO orders(order_no,user_id,type,fund_class_id,trading_account_id,source_card_id,binding_version,fund_account_id,amount,shares,application_date,confirmation_date,payment_date,request_key,request_fingerprint,purchase_rate,redemption_rate,fixed_fee,risk_level,rule_version,cutoff_time,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)","ORD-"+UUID.randomUUID().toString().substring(0,20),uid,type,fid,tid,cid,version,fa,amount,shares,application,confirmation,payment,key,fp,rate,Db.decimal(f.get("redemptionRate")),fixed,f.get("riskLevel"),f.get("ruleVersion"),f.get("cutoffTime"),account.clock());
        db.update("UPDATE orders SET planned_confirmation_date=? WHERE id=?",confirmation,oid);
        if(type.equals("PURCHASE")){db.update("UPDATE wallets SET available=available-?,frozen=frozen+? WHERE trading_account_id=?",amount,amount,tid);account.cash(uid,tid,"ORDER",String.valueOf(oid),"RESERVE",amount.negate(),amount);db.update("INSERT INTO debit_quota_entries(trading_account_id,source_type,source_id,date,amount) VALUES(?,'ORDER',?,?,?)",tid,String.valueOf(oid),account.clock().toLocalDate(),amount);}
        else for(var allocation:reserveLots(uid,tid,fid,shares)){
            db.insert("INSERT INTO order_lot_allocations(order_id,lot_id,shares,cost,source_card_id,fund_account_id,acquired_date) VALUES(?,?,?,?,?,?,?)",oid,allocation.get("lotId"),allocation.get("shares"),allocation.get("cost"),allocation.get("sourceCardId"),allocation.get("fundAccountId"),allocation.get("acquiredDate"));
            shareEntry(uid,tid,fid,Db.id(allocation.get("lotId")),"ORDER",String.valueOf(oid),"RESERVE",BigDecimal.ZERO,Db.decimal(allocation.get("shares")),BigDecimal.ZERO);
        }
        event(oid,"SUBMIT");account.notifyUser(uid,"ORDER_SUBMIT",String.valueOf(oid),"交易已受理","订单归属日："+application);return order(uid,oid);
    }
    public void checkQuota(long tid,Map<String,Object> a,BigDecimal amount){BigDecimal used=db.scalar("SELECT COALESCE(SUM(amount),0) FROM debit_quota_entries WHERE trading_account_id=? AND date=? AND released=FALSE",tid,account.clock().toLocalDate());if(amount.compareTo(Db.decimal(a.get("singleLimit")))>0||used.add(amount).compareTo(Db.decimal(a.get("dailyLimit")))>0)throw ApiException.conflict("超过模拟单笔或单日购入限额");}
    @Transactional public Map<String,Object> cancel(long uid,long oid) {
        var o=db.one("SELECT * FROM orders WHERE id=? AND user_id=? FOR UPDATE",oid,uid);account.lockAccount(uid,Db.id(o.get("tradingAccountId")));
        if("CANCELLED".equals(o.get("status")))return order(uid,oid);
        if(!"SUBMITTED".equals(o.get("status"))||!Set.of("PURCHASE","REDEMPTION").contains(o.get("type")))throw ApiException.conflict("订单已确认或不允许撤销");
        LocalDate application=LocalDate.parse(o.get("applicationDate").toString());if(!account.clock().isBefore(application.atTime(LocalTime.parse(o.get("cutoffTime").toString()))))throw ApiException.conflict("已超过申请归属日撤单截止时间");
        long tid=Db.id(o.get("tradingAccountId"));BigDecimal amount=Db.decimal(o.get("amount"));
        if("PURCHASE".equals(o.get("type"))){db.update("UPDATE wallets SET available=available+?,frozen=frozen-? WHERE trading_account_id=?",amount,amount,tid);account.cash(uid,tid,"ORDER",String.valueOf(oid),"CANCEL",amount,amount.negate());db.update("UPDATE debit_quota_entries SET released=TRUE WHERE trading_account_id=? AND source_type='ORDER' AND source_id=?",tid,String.valueOf(oid));}
        else {var allocations=orderAllocations(oid);releaseLots(allocations);for(var x:allocations)shareEntry(uid,tid,Db.id(o.get("fundClassId")),Db.id(x.get("lotId")),"ORDER",String.valueOf(oid),"RELEASE",BigDecimal.ZERO,Db.decimal(x.get("shares")).negate(),BigDecimal.ZERO);}
        db.update("UPDATE orders SET status='CANCELLED',reason='用户撤单' WHERE id=?",oid);event(oid,"CANCEL");refreshGroup(o);account.notifyUser(uid,"ORDER_CANCEL",String.valueOf(oid),"订单已撤销","冻结资金或份额已释放");return order(uid,oid);
    }
    public List<Map<String,Object>> orderAllocations(long oid){return db.rows("SELECT lot_id,shares,cost,source_card_id,fund_account_id,acquired_date FROM order_lot_allocations WHERE order_id=? ORDER BY id",oid);}
    @Transactional public List<Map<String,Object>> reserveLots(long uid,long tid,long fid,BigDecimal shares){
        account.lockAccount(uid,tid);BigDecimal left=shares;List<Map<String,Object>> result=new ArrayList<>();
        for(var lot:db.rows("SELECT * FROM position_lots WHERE user_id=? AND trading_account_id=? AND fund_class_id=? AND shares>frozen_shares ORDER BY acquired_date,id FOR UPDATE",uid,tid,fid)){
            if(left.signum()==0)break;BigDecimal total=Db.decimal(lot.get("shares")),available=total.subtract(Db.decimal(lot.get("frozenShares"))),take=left.min(available);
            BigDecimal cost=Db.money(Db.decimal(lot.get("cost")).multiply(take).divide(total,12,RoundingMode.HALF_UP));
            db.update("UPDATE position_lots SET frozen_shares=frozen_shares+? WHERE id=?",take,lot.get("id"));
            Map<String,Object> allocation=new LinkedHashMap<>();allocation.put("lotId",lot.get("id"));allocation.put("shares",take.toPlainString());allocation.put("cost",cost.toPlainString());allocation.put("sourceCardId",lot.get("sourceCardId"));allocation.put("fundAccountId",lot.get("fundAccountId"));allocation.put("acquiredDate",lot.get("acquiredDate"));result.add(allocation);left=left.subtract(take);
        }if(left.signum()>0)throw ApiException.conflict("该交易账户可用份额不足");return result;
    }
    @Transactional public void refreshAllocationCosts(List<Map<String,Object>> allocations){
        for(var x:allocations){
            var lot=db.one("SELECT * FROM position_lots WHERE id=? FOR UPDATE",x.get("lotId"));
            BigDecimal shares=Db.decimal(x.get("shares")),total=Db.decimal(lot.get("shares"));
            if(shares.signum()<=0||total.signum()<=0||shares.compareTo(total)>0||shares.compareTo(Db.decimal(lot.get("frozenShares")))>0)throw ApiException.conflict("批次冻结份额不足");
            BigDecimal remainingCost=Db.decimal(lot.get("cost"));
            BigDecimal cost=shares.compareTo(total)==0?remainingCost:Db.money(remainingCost.multiply(shares).divide(total,12,RoundingMode.HALF_UP));
            x.put("cost",Db.money(cost).toPlainString());
        }
    }
    @Transactional public void consumeLots(List<Map<String,Object>> allocations,String sourceType,String sourceId){for(var x:allocations){long lid=Db.id(x.get("lotId"));var lot=db.one("SELECT * FROM position_lots WHERE id=? FOR UPDATE",lid);
        if(db.scalar("SELECT COUNT(*) FROM share_entries WHERE lot_id=? AND source_type=? AND source_id=? AND event_type='CONSUME'",lid,sourceType,sourceId).signum()>0)continue;
        refreshAllocationCosts(List.of(x));
        // A negative distribution reduces value, not the investor's original cost basis.
        if("MONEY_INCOME".equals(sourceType)) x.put("cost","0.00");
        BigDecimal shares=Db.decimal(x.get("shares")),cost=Db.decimal(x.get("cost"));
        db.update("UPDATE position_lots SET shares=shares-?,frozen_shares=frozen_shares-?,cost=cost-? WHERE id=?",shares,shares,cost,lid);
        shareEntry(Db.id(lot.get("userId")),Db.id(lot.get("tradingAccountId")),Db.id(lot.get("fundClassId")),lid,sourceType,sourceId,"CONSUME",shares.negate(),shares.negate(),cost.negate());
    }}
    @Transactional public void releaseLots(List<Map<String,Object>> allocations){for(var x:allocations){int changed=db.update("UPDATE position_lots SET frozen_shares=frozen_shares-? WHERE id=? AND frozen_shares>=?",x.get("shares"),x.get("lotId"),x.get("shares"));if(changed!=1)throw ApiException.conflict("批次冻结份额不足");}}
    @Transactional public long addLot(long uid,long tid,long fid,long cid,long fa,BigDecimal shares,BigDecimal cost,LocalDate date,String sourceType,String sourceId){
        db.one("SELECT id FROM fund_classes WHERE id=? FOR UPDATE",fid);
        account.lockAccount(uid,tid);var prior=db.rows("SELECT id FROM position_lots WHERE trading_account_id=? AND fund_class_id=? AND source_type=? AND source_id=? AND source_card_id=?",tid,fid,sourceType,sourceId,cid);if(!prior.isEmpty())return Db.id(prior.getFirst().get("id"));
        long lid=db.insert("INSERT INTO position_lots(user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,cost,acquired_date,source_type,source_id) VALUES(?,?,?,?,?,?,?,?,?,?)",uid,tid,fid,cid,fa,shares.setScale(4,RoundingMode.HALF_UP),Db.money(cost),date,sourceType,sourceId);shareEntry(uid,tid,fid,lid,sourceType,sourceId,"ADD",shares,BigDecimal.ZERO,cost);return lid;
    }
    public void shareEntry(long uid,long tid,long fid,long lid,String sourceType,String sourceId,String event,BigDecimal shares,BigDecimal frozen,BigDecimal cost){db.insert("INSERT INTO share_entries(user_id,trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,shares_delta,frozen_delta,cost_delta,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",uid,tid,fid,lid,sourceType,sourceId,event,shares,frozen,cost,account.clock());}
    @Transactional public long payout(long uid,long tid,long cid,String sourceType,String sourceId,BigDecimal amount,LocalDate dueDate){
        var a=account.lockAccount(uid,tid);var existing=db.rows("SELECT id FROM payouts WHERE trading_account_id=? AND source_type=? AND source_id=? AND source_card_id=?",tid,sourceType,sourceId,cid);if(!existing.isEmpty())return Db.id(existing.getFirst().get("id"));
        long target=cid;Long auth=null;long version=Db.id(a.get("bindingVersion"));
        if(cid!=Db.id(a.get("bankCardId"))){var bindings=db.rows("SELECT * FROM card_bindings WHERE trading_account_id=? ORDER BY binding_version",tid);boolean found=false;for(var binding:bindings){if(Db.id(binding.get("bankCardId"))==cid){found=true;continue;}if(found){if(binding.get("changeRequestId")==null)throw ApiException.conflict("缺少历史结算卡换卡授权链");auth=Db.id(binding.get("changeRequestId"));}}if(!found||auth==null)throw ApiException.conflict("缺少来源卡换卡授权");target=Db.id(a.get("bankCardId"));}
        return db.insert("INSERT INTO payouts(user_id,trading_account_id,source_card_id,target_card_id,target_binding_version,authorization_id,source_type,source_id,amount,due_date,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",uid,tid,cid,target,version,auth,sourceType,sourceId,Db.money(amount),dueDate,account.clock());
    }
    public boolean confirmOne(long oid){return Boolean.TRUE.equals(transactions.execute(tx->{
        var source=db.one("SELECT fund_class_id FROM orders WHERE id=?",oid);db.one("SELECT id FROM fund_classes WHERE id=? FOR UPDATE",source.get("fundClassId"));
        var o=db.one("SELECT * FROM orders WHERE id=? FOR UPDATE",oid);if(!"SUBMITTED".equals(o.get("status")))return false;
        if(!Set.of("PURCHASE","REDEMPTION").contains(o.get("type")))return false;
        LocalDate planned=LocalDate.parse((o.get("plannedConfirmationDate")==null?o.get("confirmationDate"):o.get("plannedConfirmationDate")).toString());if(account.clock().isBefore(planned.atTime(9,0)))return false;
        LocalDateTime confirmedAt=account.clock();LocalDate confirmation=confirmedAt.toLocalDate();
        LocalDate application=LocalDate.parse(o.get("applicationDate").toString());long fid=Db.id(o.get("fundClassId")),uid=Db.id(o.get("userId")),tid=Db.id(o.get("tradingAccountId"));account.lockAccount(uid,tid);
        var price=funds.nav(fid,application);if(price.isEmpty()){db.update("UPDATE orders SET reason='等待所属交易日已发布净值' WHERE id=?",oid);return false;}BigDecimal nav=price.get(),fee=BigDecimal.ZERO,net=BigDecimal.ZERO,shares=Db.decimal(o.get("shares")),rounding=BigDecimal.ZERO;
        String status;
        if("PURCHASE".equals(o.get("type"))){BigDecimal amount=Db.decimal(o.get("amount"));var calc=FundMath.purchase(amount,Db.decimal(o.get("purchaseRate")),o.get("fixedFee")==null?null:Db.decimal(o.get("fixedFee")),nav);fee=calc.fee();net=calc.netAmount();shares=calc.shares();rounding=calc.roundingDifference();
            db.update("UPDATE wallets SET frozen=frozen-? WHERE trading_account_id=?",amount,tid);account.cash(uid,tid,"ORDER",String.valueOf(oid),"CONSUME",BigDecimal.ZERO,amount.negate());addLot(uid,tid,fid,Db.id(o.get("sourceCardId")),Db.id(o.get("fundAccountId")),shares,amount,confirmation,"PURCHASE",String.valueOf(oid));status="COMPLETED";
        }else {
            var allocations=orderAllocations(oid);boolean money="MONEY".equals(db.one("SELECT type FROM fund_classes WHERE id=?",fid).get("type"));
            Map<Long,BigDecimal> byCard=new LinkedHashMap<>();
            for(var x:allocations){BigDecimal s=Db.decimal(x.get("shares")),gross=Db.money(s.multiply(nav));long held=ChronoUnit.DAYS.between(LocalDate.parse(x.get("acquiredDate").toString()),application);BigDecimal f=money?BigDecimal.ZERO:FundMath.redemptionFee(s,nav,held,Db.decimal(o.get("redemptionRate")));fee=fee.add(f);BigDecimal proceeds=gross.subtract(f);net=net.add(proceeds);byCard.merge(Db.id(x.get("sourceCardId")),proceeds,BigDecimal::add);}
            consumeLots(allocations,"REDEMPTION",String.valueOf(oid));
            for(var allocation:allocations)db.update("UPDATE order_lot_allocations SET cost=? WHERE order_id=? AND lot_id=?",allocation.get("cost"),oid,allocation.get("lotId"));
            for(var e:byCard.entrySet())payout(uid,tid,e.getKey(),"REDEMPTION",String.valueOf(oid),e.getValue(),LocalDate.parse(o.get("paymentDate").toString()));status="CONFIRMED";
        }
        db.update("UPDATE orders SET status=?,nav=?,fee=?,net_amount=?,shares=?,rounding_difference=?,confirmation_date=?,confirmed_at=?,reason=NULL WHERE id=?",status,nav,fee,net,shares,rounding,confirmation,confirmedAt,oid);db.update("UPDATE nav_records SET used=TRUE WHERE fund_class_id=? AND date=?",fid,application);event(oid,"CONFIRM");refreshGroup(o);account.notifyUser(uid,"ORDER_CONFIRM",String.valueOf(oid),"交易已确认",status.equals("COMPLETED")?"申购份额已入账":"赎回已确认，等待原路支付");return true;
    }));}
    public boolean payOne(long pid){return Boolean.TRUE.equals(transactions.execute(tx->{
        var p=db.one("SELECT * FROM payouts WHERE id=? FOR UPDATE",pid);if("PAID".equals(p.get("status")))return false;LocalDate due=LocalDate.parse(p.get("dueDate").toString());if(account.clock().isBefore(due.atTime(9,30)))return false;
        long tid=Db.id(p.get("tradingAccountId")),uid=Db.id(p.get("userId"));var a=account.lockAccount(uid,tid);var card=db.one("SELECT * FROM bank_cards WHERE id=?",p.get("targetCardId"));
        if(!"RECEIVABLE".equals(card.get("receiveState"))){db.update("UPDATE payouts SET status='BLOCKED',reason='收款卡受阻，等待处置' WHERE id=?",pid);return false;}
        if(Db.id(card.get("userId"))!=uid||Db.id(a.get("bankCardId"))!=Db.id(p.get("targetCardId"))){db.update("UPDATE payouts SET status='BLOCKED',reason='结算路径已变化，等待授权改付' WHERE id=?",pid);return false;}
        account.credit(tid,"PAYOUT",String.valueOf(pid),Db.decimal(p.get("amount")));db.update("UPDATE payouts SET status='PAID',reason=NULL,paid_at=? WHERE id=?",account.clock(),pid);
        if("REDEMPTION".equals(p.get("sourceType"))){long oid=Db.id(p.get("sourceId"));if(db.scalar("SELECT COUNT(*) FROM payouts WHERE source_type='REDEMPTION' AND source_id=? AND status!='PAID'",String.valueOf(oid)).signum()==0){db.update("UPDATE orders SET status='COMPLETED' WHERE id=? AND status='CONFIRMED'",oid);event(oid,"PAY");refreshGroup(db.one("SELECT * FROM orders WHERE id=?",oid));}}
        account.notifyUser(uid,"PAYOUT",String.valueOf(pid),"模拟回款已到账",p.get("amount")+"元已回到来源交易账户");return true;
    }));}
    public Map<String,Object> run(String type,LocalDate date){
        if(date.isAfter(account.clock().toLocalDate()))throw ApiException.conflict("不能处理未来日期的任务");int processed=0,waiting=0,failed=0;List<String> details=new ArrayList<>();
        if(Set.of("MARKET","ALL").contains(type))processed+=funds.market(date);
        if(Set.of("CONFIRM","ALL").contains(type))for(var o:db.rows("SELECT id FROM orders WHERE status='SUBMITTED' AND type IN('PURCHASE','REDEMPTION') AND confirmation_date<=? ORDER BY confirmation_date,id",date)) {
            try {
                if(confirmOne(Db.id(o.get("id")))) processed++;
                else {
                    var state=db.one("SELECT status,reason FROM orders WHERE id=?",o.get("id"));
                    if("SUBMITTED".equals(state.get("status"))) { waiting++;details.add("订单"+o.get("id")+"："+Objects.requireNonNullElse(state.get("reason"),"尚未到计划确认时间")); }
                }
            } catch(Exception ex) { failed++;details.add("订单"+o.get("id")+"："+ex.getMessage()); }
        }
        if(Set.of("PAY","ALL").contains(type))for(var p:db.rows("SELECT id FROM payouts WHERE status!='PAID' AND due_date<=? ORDER BY due_date,id",date)) {
            try {
                if(payOne(Db.id(p.get("id")))) processed++;
                else {
                    var state=db.one("SELECT status,reason FROM payouts WHERE id=?",p.get("id"));
                    if(!"PAID".equals(state.get("status"))) { waiting++;details.add("付款"+p.get("id")+"："+Objects.requireNonNullElse(state.get("reason"),"尚未到计划支付时间")); }
                }
            } catch(Exception ex) { failed++;details.add("付款"+p.get("id")+"："+ex.getMessage()); }
        }
        return Map.of("processed",processed,"waiting",waiting,"failed",failed,"details",details);
    }
    public void event(long oid,String event){db.update("INSERT IGNORE INTO order_events(order_id,event_type,created_at) VALUES(?,?,?)",oid,event,account.clock());}
    public void refreshGroup(Map<String,Object> o){if(o.get("groupId")==null)return;long gid=Db.id(o.get("groupId"));var children=db.rows("SELECT status FROM orders WHERE group_id=?",gid);boolean done=children.stream().allMatch(c->Set.of("COMPLETED","CANCELLED").contains(c.get("status"))),bad=children.stream().anyMatch(c->"FAILED".equals(c.get("status")));db.update("UPDATE order_groups SET status=? WHERE id=?",bad?"PARTIAL_FAILED":done?"COMPLETED":"PROCESSING",gid);}
    @Transactional public Map<String,Object> group(long uid,Map<String,Object> input){
        db.one("SELECT id FROM fund_classes WHERE id=? FOR UPDATE",Db.id(input.get("fundClassId")));
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",uid);String key=Db.text(input,"requestKey"),fp=fingerprint(input);var old=db.rows("SELECT id,request_fingerprint FROM order_groups WHERE user_id=? AND request_key=?",uid,key);if(!old.isEmpty()){if(!fp.equals(old.getFirst().get("requestFingerprint")))throw ApiException.conflict("请求键已用于不同赎回组");return groupView(uid,Db.id(old.getFirst().get("id")));}
        Object raw=input.get("allocations");if(!(raw instanceof List<?> list)||list.isEmpty())throw ApiException.bad("须选择至少一个赎回账户");
        List<Map<String,Object>> allocations=new ArrayList<>();Set<Long> ids=new HashSet<>();for(Object x:list){if(!(x instanceof Map<?,?>))throw ApiException.bad("赎回分配格式无效");var a=(Map<String,Object>)x;long id=Db.id(a.get("tradingAccountId"));if(!ids.add(id))throw ApiException.bad("同一账户不能重复选择");allocations.add(a);}allocations.sort(Comparator.comparingLong(a->Db.id(a.get("tradingAccountId"))));for(var a:allocations)account.lockAccount(uid,Db.id(a.get("tradingAccountId")));
        long gid=db.insert("INSERT INTO order_groups(user_id,request_key,request_fingerprint,created_at) VALUES(?,?,?,?)",uid,key,fp,account.clock());
        for(var a:allocations){var in=new HashMap<String,Object>();in.put("type","REDEMPTION");in.put("fundClassId",input.get("fundClassId"));in.put("tradingAccountId",a.get("tradingAccountId"));in.put("shares",a.get("shares"));in.put("requestKey","GROUP-"+gid+"-"+a.get("tradingAccountId"));var o=submit(uid,in);db.update("UPDATE orders SET group_id=? WHERE id=?",gid,o.get("id"));}
        return groupView(uid,gid);
    }
    public Map<String,Object> groupView(long uid,long gid){var g=new HashMap<>(db.one("SELECT * FROM order_groups WHERE id=? AND user_id=?",gid,uid));g.put("orders",db.rows(orderQuery()+" WHERE o.group_id=? ORDER BY o.trading_account_id",gid));return g;}
    @Transactional public Map<String,Object> cancelGroup(long uid,long gid){db.one("SELECT * FROM order_groups WHERE id=? AND user_id=? FOR UPDATE",gid,uid);var children=db.rows("SELECT id FROM orders WHERE group_id=? ORDER BY trading_account_id",gid);for(var o:children)cancel(uid,Db.id(o.get("id")));return groupView(uid,gid);}
    public Map<String,Object> quote(long uid,Map<String,Object> in){long fid=Db.id(in.get("fundClassId")),tid=Db.id(in.get("tradingAccountId"));account.lockAccount(uid,tid);var f=funds.fund(fid);if(f.get("nav")==null)throw ApiException.conflict("暂无已发布净值用于估算");BigDecimal nav=Db.decimal(f.get("nav"));Map<String,Object> result=new LinkedHashMap<>();result.put("estimated",true);result.put("nav",nav.toPlainString());result.put("navDate",f.get("navDate"));result.put("applicationDate",funds.applicationDate(fid,account.clock()).toString());result.put("ruleVersion",f.get("ruleVersion"));
        if("PURCHASE".equals(in.get("type"))){BigDecimal amount=Db.positive(in,"amount",2),rate=Db.decimal(f.get("purchaseRate")),fixed=null;var tiers=db.rows("SELECT rate,fixed_fee FROM fee_tiers WHERE fund_class_id=? AND min_amount<=? ORDER BY min_amount DESC LIMIT 1",fid,amount);if(!tiers.isEmpty()){rate=Db.decimal(tiers.getFirst().get("rate"));if(tiers.getFirst().get("fixedFee")!=null)fixed=Db.decimal(tiers.getFirst().get("fixedFee"));}if(fixed!=null&&fixed.compareTo(amount)>=0)throw ApiException.bad("申购金额须大于该档位固定费用");var calc=FundMath.purchase(amount,rate,fixed,nav);result.put("fee",calc.fee().toPlainString());result.put("netAmount",calc.netAmount().toPlainString());result.put("shares",calc.shares().toPlainString());}
        else {BigDecimal shares=Db.positive(in,"shares",4),fee=BigDecimal.ZERO,left=shares;for(var lot:db.rows("SELECT * FROM position_lots WHERE user_id=? AND trading_account_id=? AND fund_class_id=? AND shares>frozen_shares ORDER BY acquired_date,id",uid,tid,fid)){BigDecimal take=left.min(Db.decimal(lot.get("shares")).subtract(Db.decimal(lot.get("frozenShares"))));long held=ChronoUnit.DAYS.between(LocalDate.parse(lot.get("acquiredDate").toString()),funds.applicationDate(fid,account.clock()));fee=fee.add("MONEY".equals(f.get("type"))?BigDecimal.ZERO:FundMath.redemptionFee(take,nav,held,Db.decimal(f.get("redemptionRate"))));left=left.subtract(take);if(left.signum()==0)break;}if(left.signum()>0)throw ApiException.conflict("该交易账户可用份额不足");result.put("shares",shares.toPlainString());result.put("fee",Db.money(fee).toPlainString());result.put("netAmount",Db.money(shares.multiply(nav).subtract(fee)).toPlainString());}return result;
    }
}
