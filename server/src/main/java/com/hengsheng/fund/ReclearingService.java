package com.hengsheng.fund;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fixed replay of existing applications. Application acceptance and external cash facts never run again. */
@Service
public class ReclearingService {
    private final Db db;private final TradeService trades;private final AccountService accounts;private final ConfirmationService confirmations;
    private final FundService funds;
    private final ObjectProvider<ClearingService> clearing;private final TransactionTemplate tx;private final ObjectMapper json;
    public ReclearingService(Db db,TradeService trades,AccountService accounts,ConfirmationService confirmations,ObjectProvider<ClearingService> clearing,PlatformTransactionManager manager,ObjectMapper json,FundService funds){this.db=db;this.trades=trades;this.accounts=accounts;this.confirmations=confirmations;this.clearing=clearing;this.json=json;this.funds=funds;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    private static long id(Map<String,Object> row,String key){return Db.id(row.get(key));}
    private static BigDecimal num(Map<String,Object> row,String key){return Db.decimal(row.get(key));}
    private static LocalDate date(Object value){return LocalDate.parse(value.toString().substring(0,10));}
    private static String ids(Collection<Long> values){return values.isEmpty()?"0":String.join(",",values.stream().map(String::valueOf).toList());}
    @SuppressWarnings("unchecked") private Map<String,Object> read(Object value){return json.readValue(value.toString(),Map.class);}
    private List<Map<String,Object>> rows(Map<String,Object> data,String key){return (List<Map<String,Object>>)data.get(key);}
    private Map<Long,Map<String,Object>> index(List<Map<String,Object>> rows){var result=new LinkedHashMap<Long,Map<String,Object>>();for(var row:rows)result.put(id(row,"id"),new LinkedHashMap<>(row));return result;}
    private List<Map<String,Object>> members(long revisionSetId){return db.rows("""
            SELECT DISTINCT o.trading_account_id FROM orders o JOIN nav_revision_items r ON r.fund_class_id=o.fund_class_id AND r.market_date=o.application_date
            WHERE r.revision_set_id=? AND o.status IN('COMPLETED','CONFIRMED')
            UNION SELECT DISTINCT c.trading_account_id FROM conversions c JOIN nav_revision_items r ON r.market_date=c.application_date AND r.fund_class_id IN(c.source_class_id,c.target_class_id)
            WHERE r.revision_set_id=? AND c.status='COMPLETED'
            UNION SELECT DISTINCT e.trading_account_id FROM dividend_entitlements e JOIN dividend_events d ON d.id=e.event_id JOIN nav_revision_items r ON r.fund_class_id=d.fund_class_id AND r.market_date=d.ex_date
            WHERE r.revision_set_id=? AND e.mode='REINVEST' ORDER BY trading_account_id
            """,revisionSetId,revisionSetId,revisionSetId);}
    private Map<String,Object> snapshot(long revisionSetId){
        var revision=db.one("SELECT * FROM nav_revision_sets WHERE id=?",revisionSetId);
        var revisions=db.rows("SELECT * FROM nav_revision_items WHERE revision_set_id=? ORDER BY fund_class_id,market_date",revisionSetId);
        var members=members(revisionSetId);
        var aids=members.stream().map(r->id(r,"tradingAccountId")).toList();String in=ids(aids);
        var s=new LinkedHashMap<String,Object>();s.put("revision",revision);s.put("revisions",revisions);s.put("members",members);
        s.put("accounts",db.rows("SELECT t.*,w.available,w.frozen FROM trading_accounts t JOIN wallets w ON w.trading_account_id=t.id WHERE t.id IN ("+in+") ORDER BY t.id"));
        s.put("cards",db.rows("SELECT c.* FROM bank_cards c JOIN card_bindings b ON b.bank_card_id=c.id WHERE b.trading_account_id IN ("+in+") ORDER BY c.id,b.id"));
        for(String table:List.of("orders","conversions","position_lots","cash_entries","payouts","card_bindings","dividend_entitlements","money_income_snapshots","offering_orders","liquidation_items","money_exit_disposals","money_tail_disposals"))s.put(table,db.rows("SELECT * FROM "+table+" WHERE trading_account_id IN ("+in+") ORDER BY id"));
        s.put("extensionInputs",db.rows("SELECT * FROM extension_replay_inputs WHERE trading_account_id IN ("+in+") ORDER BY id"));
        s.put("dividendEvents",db.rows("SELECT * FROM dividend_events WHERE id IN(SELECT event_id FROM dividend_entitlements WHERE trading_account_id IN ("+in+")) ORDER BY id"));
        s.put("dividendLots",db.rows("SELECT * FROM dividend_lot_entitlements WHERE entitlement_id IN(SELECT id FROM dividend_entitlements WHERE trading_account_id IN ("+in+")) ORDER BY entitlement_id,lot_id"));
        s.put("incomeRates",db.rows("SELECT * FROM money_income_records WHERE fund_class_id IN(SELECT fund_class_id FROM money_income_snapshots WHERE trading_account_id IN ("+in+")) ORDER BY id"));
        s.put("offeringEvents",db.rows("SELECT * FROM offering_events WHERE fund_class_id IN(SELECT fund_class_id FROM offering_orders WHERE trading_account_id IN ("+in+")) ORDER BY id"));
        s.put("share_entries",db.rows("SELECT * FROM share_entries WHERE trading_account_id IN ("+in+") AND effect_state='ACTIVE' AND reversal_of IS NULL ORDER BY id"));
        s.put("confirmations",db.rows("SELECT c.* FROM trade_confirmations c WHERE (source_type='ORDER' AND source_id IN (SELECT id FROM orders WHERE trading_account_id IN ("+in+"))) OR (source_type='CONVERSION' AND source_id IN (SELECT id FROM conversions WHERE trading_account_id IN ("+in+"))) ORDER BY c.id"));
        s.put("allocations",db.rows("SELECT a.* FROM order_lot_allocations a JOIN orders o ON o.id=a.order_id WHERE o.trading_account_id IN ("+in+") ORDER BY a.id"));
        s.put("conversionAllocations",db.rows("SELECT a.* FROM conversion_allocations a JOIN conversions c ON c.id=a.conversion_id WHERE c.trading_account_id IN ("+in+") ORDER BY a.id"));
        s.put("navs",db.rows("SELECT n.* FROM nav_records n WHERE n.id IN (SELECT n2.id FROM nav_records n2 JOIN nav_revision_items r ON n2.fund_class_id=r.fund_class_id AND n2.date=r.market_date WHERE r.revision_set_id=?) OR n.fund_class_id IN (SELECT fund_class_id FROM position_lots WHERE trading_account_id IN ("+in+")) ORDER BY n.fund_class_id,n.date",revisionSetId));
        s.put("funds",db.rows("SELECT id,type,state FROM fund_classes WHERE id IN (SELECT fund_class_id FROM position_lots WHERE trading_account_id IN ("+in+")) ORDER BY id"));
        return s;
    }
    public Map<String,Object> preview(long revisionSetId,long actorId){
        var snapshot=snapshot(revisionSetId);var replay=replay(snapshot);String version=TradeService.fingerprint(snapshot);
        var result=new LinkedHashMap<String,Object>();result.put("scopeVersion",version);result.put("affectedAccounts",rows(snapshot,"members"));result.put("changes",Db.jsonDecimals(replay.changes));result.put("blockers",Db.jsonDecimals(replay.blockers));
        long pid=db.insert("INSERT INTO reclearing_previews(revision_set_id,scope_version,actor_id,state,result) VALUES(?,?,?,?,?)",revisionSetId,version,actorId,replay.blockers.isEmpty()?"READY":"BLOCKED",json.writeValueAsString(result));result.put("previewId",pid);return result;
    }
    public long execute(long previewId,String scopeVersion,String requestKey,long actorId){return tx.execute(status->{
        var p=db.one("SELECT * FROM reclearing_previews WHERE id=? FOR UPDATE",previewId);
        if(!Objects.equals(scopeVersion,p.get("scopeVersion")))throw ApiException.conflict("预演版本不匹配，请重新预演");
        if(p.get("runId")!=null)return clearing.getObject().bindRequestAlias(id(p,"runId"),requestKey,actorId);
        if(!funds.nextOpen(accounts.clock().toLocalDate()).equals(accounts.clock().toLocalDate()))throw ApiException.conflict("交易重清须在开放工作日执行，请于下个开放日提交");
        if(!"READY".equals(p.get("state")))throw ApiException.conflict("预演存在阻断项，不能重清");
        var current=snapshot(id(p,"revisionSetId"));lockAccounts(current);validate(p,snapshot(id(p,"revisionSetId")));
        long run=clearing.getObject().submit("RECLEAR",accounts.clock().toLocalDate(),Map.of("previewId",previewId,"scopeVersion",scopeVersion),actorId,requestKey);
        if(!"REJECTED_QUEUE_FULL".equals(db.one("SELECT state FROM clearing_runs WHERE id=?",run).get("state")))db.update("UPDATE reclearing_previews SET run_id=?,state='QUEUED' WHERE id=?",run,previewId);return run;
    });}
    private void lockAccounts(Map<String,Object> snapshot){for(var row:rows(snapshot,"accounts")){db.one("SELECT id FROM trading_accounts WHERE id=? FOR UPDATE",row.get("id"));db.one("SELECT trading_account_id FROM wallets WHERE trading_account_id=? FOR UPDATE",row.get("id"));}}
    private void validate(Map<String,Object> preview,Map<String,Object> current){if(!Objects.equals(preview.get("scopeVersion"),TradeService.fingerprint(current)))throw ApiException.conflict("资产、行情或依赖成员已变化，预演已过期，请重新预演");}
    public Map<String,Object> apply(long runId){
        var p=db.one("SELECT * FROM reclearing_previews WHERE run_id=?",runId);if("APPLIED".equals(p.get("state")))return Map.of("processed",0,"waiting",0,"failed",0,"details",List.of("重清已发布"));
        var input=snapshot(id(p,"revisionSetId"));validate(p,input);var replay=replay(input);if(!replay.blockers.isEmpty())throw ApiException.conflict("预演存在阻断项，不能发布");
        return tx.execute(status->{
            var preview=db.one("SELECT * FROM reclearing_previews WHERE id=? FOR UPDATE",p.get("id"));if("APPLIED".equals(preview.get("state")))return Map.of("processed",0,"waiting",0,"failed",0,"details",List.of());
            lockAccounts(input);validate(preview,snapshot(id(preview,"revisionSetId")));publish(runId,input,replay);
            // Publication of the price pointer is the short final critical section.
            for(var r:rows(input,"revisions")){var nav=db.one("SELECT * FROM nav_records WHERE fund_class_id=? AND date=? FOR UPDATE",r.get("fundClassId"),r.get("marketDate"));if(num(nav,"nav").compareTo(num(r,"previousNav"))!=0||!Objects.equals(nav.get("activeRevisionId"),r.get("previousRevisionId")))throw ApiException.conflict("修订基准行情已变化，请重新预演");}
            if(!members(id(preview,"revisionSetId")).equals(rows(input,"members")))throw ApiException.conflict("新确认扩大影响范围，预演已过期，请重新预演");
            for(var r:rows(input,"revisions"))db.update("UPDATE nav_records SET nav=?,active_revision_id=? WHERE fund_class_id=? AND date=?",r.get("nav"),r.get("id"),r.get("fundClassId"),r.get("marketDate"));
            db.update("UPDATE nav_revision_sets SET state='PUBLISHED',published_run_id=? WHERE id=?",runId,preview.get("revisionSetId"));db.update("UPDATE reclearing_previews SET state='APPLIED' WHERE id=?",preview.get("id"));
            return Map.of("processed",replay.changes.size()+rows(input,"revisions").size(),"waiting",0,"failed",0,"details",List.of("整个影响范围已原子发布"));
        });
    }
    private static final class Replay {
        final List<Map<String,Object>> changes=new ArrayList<>(),blockers=new ArrayList<>(),effects=new ArrayList<>();
        final Map<Long,Map<String,Object>> lots,orders,conversions;
        final Map<String,List<Map<String,Object>>> allocations=new LinkedHashMap<>();
        final Map<Long,Map<String,Object>> results=new LinkedHashMap<>(),conversionResults=new LinkedHashMap<>();
        final Map<Long,BigDecimal> cashDelta=new LinkedHashMap<>();
        final Map<Long,BigDecimal> payouts=new LinkedHashMap<>();
        final Map<Long,Map<String,Object>> rights=new LinkedHashMap<>(),incomes=new LinkedHashMap<>(),exits=new LinkedHashMap<>();
        final Map<Long,List<Map<String,Object>>> dividendLots=new LinkedHashMap<>();
        final List<Map<String,Object>> directCash=new ArrayList<>();
        Replay(Map<Long,Map<String,Object>> lots,Map<Long,Map<String,Object>> orders,Map<Long,Map<String,Object>> conversions){this.lots=lots;this.orders=orders;this.conversions=conversions;}
    }
    private void block(Replay r,String source,long sourceId,long account,String reason,BigDecimal shortage){r.blockers.add(Map.of("sourceType",source,"sourceId",sourceId,"tradingAccountId",account,"reason",reason,"shortage",shortage.toPlainString()));}
    private Replay replay(Map<String,Object> input){
        var r=new Replay(index(rows(input,"position_lots")),index(rows(input,"orders")),index(rows(input,"conversions")));
        for(var a:rows(input,"accounts")){
            BigDecimal available=BigDecimal.ZERO,frozen=BigDecimal.ZERO;for(var e:rows(input,"cash_entries"))if(id(e,"tradingAccountId")==id(a,"id")){available=available.add(num(e,"availableDelta"));frozen=frozen.add(num(e,"frozenDelta"));}
            if(available.compareTo(num(a,"available"))!=0||frozen.compareTo(num(a,"frozen"))!=0)block(r,"WALLET",id(a,"id"),id(a,"id"),"原现金基线与不可变流水不一致",num(a,"available").subtract(available).abs());
        }
        for(var lot:r.lots.values()){
            BigDecimal shares=BigDecimal.ZERO,frozen=BigDecimal.ZERO,cost=BigDecimal.ZERO;for(var e:rows(input,"share_entries"))if(id(e,"lotId")==id(lot,"id")){shares=shares.add(num(e,"sharesDelta"));frozen=frozen.add(num(e,"frozenDelta"));cost=cost.add(num(e,"costDelta"));}
            if(shares.compareTo(num(lot,"shares"))!=0||frozen.compareTo(num(lot,"frozenShares"))!=0||cost.compareTo(num(lot,"cost"))!=0)block(r,"LOT",id(lot,"id"),id(lot,"tradingAccountId"),"原份额、冻结或成本基线无法核验",num(lot,"shares").subtract(shares).abs());
        }
        for(var lot:r.lots.values()){lot.put("shares",BigDecimal.ZERO);lot.put("frozenShares",BigDecimal.ZERO);lot.put("cost",BigDecimal.ZERO);}
        for(String table:List.of("liquidation_items","money_tail_disposals"))for(var row:rows(input,table))block(r,table,id(row,"id"),id(row,"tradingAccountId"),"已清盘边界禁止重清",BigDecimal.ZERO);
        for(var row:rows(input,"dividend_entitlements"))if(extension(input,"DIVIDEND",id(row,"id"),"REGISTER")==null)block(r,"DIVIDEND",id(row,"id"),id(row,"tradingAccountId"),"历史分红缺少登记输入快照",BigDecimal.ZERO);
        for(var row:rows(input,"money_income_snapshots"))if(extension(input,"MONEY_INCOME",id(row,"id"),"COMPLETED".equals(row.get("status"))?"APPLY":"SNAPSHOT")==null)block(r,"MONEY_INCOME",id(row,"id"),id(row,"tradingAccountId"),"历史收益缺少处理输入快照",BigDecimal.ZERO);
        for(var fund:rows(input,"funds"))if("LIQUIDATED".equals(fund.get("state")))block(r,"FUND",id(fund,"id"),0,"已清盘边界禁止重清",BigDecimal.ZERO);
        for(var revision:rows(input,"revisions")){var nav=rows(input,"navs").stream().filter(n->id(n,"fundClassId")==id(revision,"fundClassId")&&n.get("date").equals(revision.get("marketDate"))).findFirst();if(nav.isEmpty()||num(nav.get(),"nav").compareTo(num(revision,"previousNav"))!=0||!Objects.equals(nav.get().get("activeRevisionId"),revision.get("previousRevisionId")))block(r,"NAV",id(revision,"id"),0,"修订基准已变化",BigDecimal.ZERO);}
        var grouped=new LinkedHashMap<String,List<Map<String,Object>>>();
        var entries=new ArrayList<>(rows(input,"share_entries").stream().filter(e->!"MONEY_INCOME".equals(e.get("sourceType"))).toList());
        for(var row:rows(input,"extensionInputs"))if(("DIVIDEND".equals(row.get("sourceType"))&&"REGISTER".equals(row.get("phase")))||("MONEY_INCOME".equals(row.get("sourceType"))&&("APPLY".equals(row.get("phase"))||("SNAPSHOT".equals(row.get("phase"))&&extension(input,"MONEY_INCOME",id(row,"sourceId"),"APPLY")==null)))){
            var e=new LinkedHashMap<>(row);e.put("eventType",row.get("phase"));e.put("sourceId",row.get("sourceId").toString());e.put("createdAt",row.get("appliedAt"));e.put("synthetic",true);
            if("MONEY_INCOME".equals(row.get("sourceType")))e.put("replayDate",date(row.get("appliedAt")));entries.add(e);
        }
        entries.sort(Comparator.comparing((Map<String,Object> e)->e.get("replayDate")==null?effective(e,r):date(e.get("replayDate"))).thenComparingLong(this::sequence).thenComparing(e->Boolean.TRUE.equals(e.get("synthetic"))?0:1));
        for(var e:entries){String key=e.get("sourceType")+":"+e.get("sourceId")+":"+e.get("eventType");grouped.computeIfAbsent(key,k->new ArrayList<>()).add(e);}
        for(var group:grouped.values()){
            var e=group.getFirst();String source=e.get("sourceType").toString(),event=e.get("eventType").toString(),sid=e.get("sourceId").toString();long aid=id(e,"tradingAccountId");
            try{
                if("DIVIDEND".equals(source))dividend(input,r,group);
                else if("MONEY_INCOME".equals(source))income(input,r,e);
                else if("OFFERING".equals(source)&&"ADD".equals(event)){
                    var o=r.orders.get(Long.parseLong(sid));requireConfirmation(o);
                    var confirmation=rows(input,"confirmations").stream().filter(c->id(c,"id")==id(o,"currentConfirmationId")).findFirst().orElseThrow(()->new IllegalStateException("成立认购缺少原确认"));var fixed=read(confirmation.get("result"));
                    if(!"OFFERING".equals(fixed.get("type"))||num(fixed,"shares").compareTo(num(e,"sharesDelta"))!=0)throw new IllegalStateException("成立份额与固定认购确认不一致");
                    addEffect(r,e,r.lots.get(id(e,"lotId")),num(fixed,"shares"),BigDecimal.ZERO,num(fixed,"amount"));
                }else if("PURCHASE".equals(source)&&"ADD".equals(event)){
                    long oid=Long.parseLong(sid);var o=r.orders.get(oid);requireConfirmation(o);var result=trades.calculateConfirmation(o,List.of(),Map.of("nav",nav(input,id(o,"fundClassId"),o.get("applicationDate"))),effective(e,r));r.results.put(oid,result);
                    var lot=r.lots.get(id(e,"lotId"));addEffect(r,e,lot,num(result,"shares"),BigDecimal.ZERO,num(o,"amount"));change(r,"ORDER",o,result,e);
                }else if("ORDER".equals(source)||"REDEMPTION".equals(source)||("CONVERSION".equals(source)&&!"ADD".equals(event))){
                    long oid=Long.parseLong(sid);boolean conversion="CONVERSION".equals(source);var o=(conversion?r.conversions:r.orders).get(oid);String key=(conversion?"CONVERSION:":"ORDER:")+oid;
                    if(o==null)throw new IllegalStateException("原申请缺失");
                    if("RESERVE".equals(event)){var allocations=reserve(input,r,o,conversion,e);r.allocations.put(key,allocations);}
                    else if("RELEASE".equals(event)){for(var a:requiredAllocations(r,key)){var lot=r.lots.get(id(a,"lotId"));addEffect(r,e,lot,BigDecimal.ZERO,num(a,"shares").negate(),BigDecimal.ZERO);}}
                    else if("CONSUME".equals(event)){
                        requireConfirmation(o);var allocations=requiredAllocations(r,key);for(var a:allocations){var lot=r.lots.get(id(a,"lotId"));BigDecimal take=num(a,"shares"),total=num(lot,"shares"),cost=take.compareTo(total)==0?num(lot,"cost"):Db.money(num(lot,"cost").multiply(take).divide(total,12,RoundingMode.HALF_UP));a.put("cost",cost);addEffect(r,e,lot,take.negate(),take.negate(),cost.negate());}
                        if(conversion)convert(input,r,o,allocations,e);else{boolean money=rows(input,"funds").stream().anyMatch(f->id(f,"id")==id(o,"fundClassId")&&"MONEY".equals(f.get("type")));var result=trades.calculateConfirmation(o,allocations,Map.of("nav",nav(input,id(o,"fundClassId"),o.get("applicationDate")),"money",money),effective(e,r));r.results.put(oid,result);change(r,"ORDER",o,result,e);}
                    }else throw new IllegalStateException("无法识别的份额效果 "+event);
                }else if(!("CONVERSION".equals(source)&&"ADD".equals(event)))throw new IllegalStateException("缺少来源业务重放依据 "+source);
            }catch(RuntimeException ex){block(r,source,sourceNumber(sid),aid,ex.getMessage(),BigDecimal.ZERO);}
        }
        for(var o:r.orders.values())if(Set.of("COMPLETED","CONFIRMED").contains(o.get("status"))&&Set.of("PURCHASE","REDEMPTION").contains(o.get("type"))&&!r.results.containsKey(id(o,"id")))block(r,"ORDER",id(o,"id"),id(o,"tradingAccountId"),"已确认申请缺少完整原资产流水",BigDecimal.ZERO);
        for(var c:r.conversions.values())if("COMPLETED".equals(c.get("status"))&&!r.conversionResults.containsKey(id(c,"id")))block(r,"CONVERSION",id(c,"id"),id(c,"tradingAccountId"),"已确认转换缺少完整原资产流水",BigDecimal.ZERO);
        for(var p:rows(input,"payouts")){
            if("REVERSED".equals(p.get("status")))continue;
            long sid=sourceNumber(p.get("sourceId").toString());BigDecimal amount;
            if("REDEMPTION".equals(p.get("sourceType"))){var result=r.results.get(sid);if(result==null)continue;amount=Db.decimal(((Map<?,?>)result.get("byCard")).get(p.get("sourceCardId").toString()));}
            else if("DIVIDEND".equals(p.get("sourceType"))&&r.rights.containsKey(sid))amount=num(r.rights.get(sid),"amount");
            else if("MONEY_EXIT_INCOME".equals(p.get("sourceType"))&&r.incomes.containsKey(sid))amount=num(r.incomes.get(sid),"cashAdjustment").max(BigDecimal.ZERO);
            else continue;
            r.payouts.put(id(p,"id"),amount);
            if("PAID".equals(p.get("status"))){
                boolean credit=rows(input,"cash_entries").stream().anyMatch(c->"PAYOUT".equals(c.get("sourceType"))&&c.get("sourceId").toString().equals(p.get("id").toString())&&"CREDIT".equals(c.get("eventType"))&&num(c,"availableDelta").compareTo(num(p,"amount"))==0);
                if(!credit)block(r,"PAYOUT",id(p,"id"),id(p,"tradingAccountId"),"已支付付款缺少原现金入账依据",BigDecimal.ZERO);
                r.cashDelta.merge(id(p,"tradingAccountId"),amount.subtract(num(p,"amount")),BigDecimal::add);
            }
        }
        for(var a:rows(input,"accounts")){BigDecimal finalCash=num(a,"available").add(r.cashDelta.getOrDefault(id(a,"id"),BigDecimal.ZERO));if(finalCash.signum()<0)block(r,"WALLET",id(a,"id"),id(a,"id"),"原来源账户可用资金不足，不能跨卡回收",finalCash.negate());}
        r.changes.sort(Comparator.comparing((Map<String,Object> c)->date(c.get("effectiveDate"))).thenComparingLong(c->id(c,"operationSequence")));
        return r;
    }
    private Map<String,Object> extension(Map<String,Object> input,String type,long source,String phase){return rows(input,"extensionInputs").stream().filter(e->type.equals(e.get("sourceType"))&&id(e,"sourceId")==source&&phase.equals(e.get("phase"))).findFirst().orElse(null);}
    private BigDecimal historical(Replay r,long lot,LocalDate through,boolean inclusive){return r.effects.stream().filter(e->id(e,"lotId")==lot&&(inclusive?!date(e.get("effectiveDate")).isAfter(through):date(e.get("effectiveDate")).isBefore(through))).map(e->num(e,"sharesDelta")).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private void dividend(Map<String,Object> input,Replay r,List<Map<String,Object>> group){
        var e=group.getFirst();long sid=sourceNumber(e.get("sourceId").toString());var before=rows(input,"dividend_entitlements").stream().filter(x->id(x,"id")==sid).findFirst().orElseThrow();
        var captured=extension(input,"DIVIDEND",sid,"REGISTER");if(captured==null)throw new IllegalStateException("历史分红缺少登记输入快照");var terms=read(captured.get("payload"));
        if("REGISTER".equals(e.get("eventType"))){
            var result=new LinkedHashMap<>(before);var lots=new ArrayList<Map<String,Object>>();BigDecimal shares=BigDecimal.ZERO,amount=BigDecimal.ZERO;
            for(var lot:r.lots.values())if(id(lot,"tradingAccountId")==id(before,"tradingAccountId")&&id(lot,"sourceCardId")==id(before,"sourceCardId")&&id(lot,"fundClassId")==id(terms,"fundClassId")){
                BigDecimal eligible=historical(r,id(lot,"id"),date(terms.get("recordDate")),true);if(eligible.signum()<=0)continue;
                BigDecimal value=AdvancedAmounts.dividend(eligible,num(terms,"perShare"));lots.add(new LinkedHashMap<>(Map.of("lotId",lot.get("id"),"shares",eligible,"amount",value)));shares=shares.add(eligible);amount=amount.add(value);
            }
            result.put("shares",shares);result.put("amount",amount);r.rights.put(sid,result);r.dividendLots.put(sid,lots);change(r,"DIVIDEND",before,result,e);
        }else {
            var result=r.rights.get(sid);if(result==null)throw new IllegalStateException("缺少分红登记日权益");
            if("COST_ADJUST".equals(e.get("eventType"))){
                for(var item:r.dividendLots.get(sid)){var lot=r.lots.get(id(item,"lotId"));BigDecimal reduction=Db.money(num(item,"amount").multiply(num(lot,"shares").min(num(item,"shares"))).divide(num(item,"shares"),12,RoundingMode.HALF_UP));addEffect(r,e,lot,BigDecimal.ZERO,BigDecimal.ZERO,reduction.negate());}
            }else if("ADD".equals(e.get("eventType"))){
                var lot=r.lots.get(id(e,"lotId"));BigDecimal units=num(result,"amount").divide(nav(input,id(terms,"fundClassId"),terms.get("exDate")),4,RoundingMode.HALF_UP);addEffect(r,e,lot,units,BigDecimal.ZERO,num(result,"amount"));
            }else throw new IllegalStateException("无法识别的分红效果");
        }
    }
    private Map<String,Object> incomeEffect(Map<String,Object> action,Map<String,Object> row,String event){var e=new LinkedHashMap<>(action);e.put("userId",row.get("userId"));e.put("fundClassId",row.get("fundClassId"));e.put("eventType",event);e.put("effectiveDate",row.get("date"));return e;}
    private void income(Map<String,Object> input,Replay r,Map<String,Object> action){
        long sid=sourceNumber(action.get("sourceId").toString());var before=rows(input,"money_income_snapshots").stream().filter(x->id(x,"id")==sid).findFirst().orElseThrow();var terms=read(action.get("payload"));
        long aid=id(before,"tradingAccountId"),fid=id(before,"fundClassId"),card=id(before,"sourceCardId");LocalDate day=date(before.get("date"));
        var lots=r.lots.values().stream().filter(l->id(l,"tradingAccountId")==aid&&id(l,"fundClassId")==fid&&id(l,"sourceCardId")==card).sorted(Comparator.comparing((Map<String,Object> l)->date(l.get("acquiredDate"))).thenComparingLong(this::lotOrder)).toList();
        BigDecimal qualified=lots.stream().map(l->historical(r,id(l,"id"),day,false)).reduce(BigDecimal.ZERO,BigDecimal::add),carried=BigDecimal.ZERO;
        var prior=r.incomes.values().stream().filter(x->id(x,"tradingAccountId")==aid&&id(x,"fundClassId")==fid&&id(x,"sourceCardId")==card&&date(x.get("date")).isBefore(day)).max(Comparator.comparing(x->date(x.get("date"))));if(prior.isPresent())carried=num(prior.get(),"tail");
        BigDecimal income=AdvancedAmounts.moneyShares(qualified,num(terms,"rate")),combined=income.add(carried),held=lots.stream().map(l->num(l,"shares")).reduce(BigDecimal.ZERO,BigDecimal::add),frozen=lots.stream().map(l->num(l,"frozenShares")).reduce(BigDecimal.ZERO,BigDecimal::add);
        if("PENDING".equals(before.get("status"))){var result=new LinkedHashMap<>(before);result.put("shares",qualified);result.put("income",income);r.incomes.put(sid,result);change(r,"MONEY_INCOME",before,result,action);return;}
        BigDecimal units=combined.setScale(4,RoundingMode.DOWN),cash=BigDecimal.ZERO,tail;
        boolean exited=held.signum()==0&&frozen.signum()==0;
        if(exited){units=BigDecimal.ZERO;cash=Db.money(combined);tail=BigDecimal.ZERO;var disposal=new LinkedHashMap<>(before);disposal.put("carriedTail",carried);disposal.put("originalTail",combined.subtract(cash).setScale(12,RoundingMode.HALF_UP));disposal.put("settledAmount",cash);r.exits.put(sid,disposal);}
        else {
            if(units.signum()>0){
                var matches=lots.stream().filter(l->"MONEY_INCOME".equals(l.get("sourceType"))&&l.get("sourceId").toString().equals(String.valueOf(sid))).toList();Map<String,Object> lot;
                if(matches.isEmpty()){lot=new LinkedHashMap<>();for(String key:List.of("userId","tradingAccountId","fundClassId","sourceCardId","fundAccountId"))lot.put(key,before.get(key));long lid=-r.lots.size()-1;lot.put("id",lid);lot.put("sourceType","MONEY_INCOME");lot.put("sourceId",String.valueOf(sid));lot.put("acquiredDate",day);lot.put("shares",BigDecimal.ZERO);lot.put("frozenShares",BigDecimal.ZERO);lot.put("cost",BigDecimal.ZERO);r.lots.put(lid,lot);}else lot=matches.getFirst();
                if(held.compareTo(frozen)==0)throw new IllegalStateException("末期收益原来源份额全部冻结，须先处置原申请");addEffect(r,incomeEffect(action,before,"ADD"),lot,units,BigDecimal.ZERO,BigDecimal.ZERO);
            }else if(units.signum()<0){
                BigDecimal free=held.subtract(frozen),loss=units.negate();if(free.compareTo(loss)<0&&frozen.signum()>0)throw new IllegalStateException("负收益来源份额仍冻结");BigDecimal left=loss.min(free);cash=Db.money(loss.subtract(left)).negate();units=left.negate();
                for(var lot:lots){BigDecimal take=left.min(num(lot,"shares").subtract(num(lot,"frozenShares")));if(take.signum()>0)addEffect(r,incomeEffect(action,before,"CONSUME"),lot,take.negate(),BigDecimal.ZERO,BigDecimal.ZERO);left=left.subtract(take);if(left.signum()==0)break;}
            }
            tail=combined.subtract(units).subtract(cash).setScale(12,RoundingMode.HALF_UP);
        }
        var result=new LinkedHashMap<>(before);result.put("shares",qualified);result.put("income",income);result.put("appliedShares",units);result.put("cashAdjustment",cash);result.put("tail",tail);r.incomes.put(sid,result);change(r,"MONEY_INCOME",before,result,action);
        BigDecimal oldDebit=num(before,"cashAdjustment").min(BigDecimal.ZERO),newDebit=cash.min(BigDecimal.ZERO);
        if(oldDebit.signum()<0||newDebit.signum()<0){
            var old=rows(input,"cash_entries").stream().filter(c->Set.of("MONEY_INCOME","MONEY_EXIT_INCOME").contains(c.get("sourceType"))&&c.get("sourceId").toString().equals(String.valueOf(sid))&&"LOSS_DEBIT".equals(c.get("eventType"))).max(Comparator.comparingLong(c->id(c,"id")));
            if(oldDebit.signum()<0&&(old.isEmpty()||num(old.get(),"availableDelta").compareTo(oldDebit)!=0))throw new IllegalStateException("收益现金损失缺少原入账流水");
            var effect=new LinkedHashMap<>(before);effect.put("oldEntry",old.orElse(null));effect.put("amount",newDebit);effect.put("sourceType",exited?"MONEY_EXIT_INCOME":"MONEY_INCOME");r.directCash.add(effect);r.cashDelta.merge(aid,newDebit.subtract(oldDebit),BigDecimal::add);
        }
    }
    private long sourceNumber(String value){try{return Long.parseLong(value.split("-")[0]);}catch(NumberFormatException ex){return 0;}}
    private void requireConfirmation(Map<String,Object> row){if(row==null||row.get("currentConfirmationId")==null)throw new IllegalStateException("旧确认缺少不可变结果快照");}
    private long sequence(Map<String,Object> e){return e.get("operationSequence")==null?id(e,"id"):id(e,"operationSequence");}
    private long lotOrder(Map<String,Object> lot){return lot.get("replaySequence")==null?id(lot,"id"):id(lot,"replaySequence");}
    private LocalDate effective(Map<String,Object> e,Replay r){if(e.get("effectiveDate")!=null)return date(e.get("effectiveDate"));if("ADD".equals(e.get("eventType")))return date(r.lots.get(id(e,"lotId")).get("acquiredDate"));if("REDEMPTION".equals(e.get("sourceType"))){var o=r.orders.get(sourceNumber(e.get("sourceId").toString()));if(o!=null)return date(o.get("confirmationDate"));}if("CONVERSION".equals(e.get("sourceType"))&&"CONSUME".equals(e.get("eventType"))){var c=r.conversions.get(sourceNumber(e.get("sourceId").toString()));if(c!=null)return date(c.get("confirmationDate"));}return date(e.get("createdAt"));}
    private BigDecimal nav(Map<String,Object> input,long fid,Object day){if(rows(input,"funds").stream().anyMatch(f->id(f,"id")==fid&&"MONEY".equals(f.get("type"))))return BigDecimal.ONE;for(var n:rows(input,"revisions"))if(id(n,"fundClassId")==fid&&date(n.get("marketDate")).equals(date(day)))return num(n,"nav");return rows(input,"navs").stream().filter(n->id(n,"fundClassId")==fid&&date(n.get("date")).equals(date(day))&&Db.yes(n.get("published"))).map(n->num(n,"nav")).findFirst().orElseThrow(()->new IllegalStateException("缺少原申请日已发布行情"));}
    private List<Map<String,Object>> requiredAllocations(Replay r,String key){var a=r.allocations.get(key);if(a==null)throw new IllegalStateException("缺少原申请冻结操作");return a;}
    private List<Map<String,Object>> reserve(Map<String,Object> input,Replay r,Map<String,Object> order,boolean conversion,Map<String,Object> e){
        long fid=id(order,conversion?"sourceClassId":"fundClassId"),aid=id(order,"tradingAccountId");BigDecimal left=num(order,"shares");var allocations=new ArrayList<Map<String,Object>>();
        var sourceLimits=new LinkedHashMap<Long,BigDecimal>();for(var a:rows(input,conversion?"conversionAllocations":"allocations"))if(id(a,conversion?"conversionId":"orderId")==id(order,"id"))sourceLimits.merge(id(a,"sourceCardId"),num(a,"shares"),BigDecimal::add);
        if(sourceLimits.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(left)!=0)throw new IllegalStateException("原申请来源分配快照不完整");
        var lots=r.lots.values().stream().filter(l->id(l,"tradingAccountId")==aid&&id(l,"fundClassId")==fid&&!date(l.get("acquiredDate")).isAfter(effective(e,r))).sorted(Comparator.comparing((Map<String,Object> l)->l.get("acquiredDate").toString()).thenComparingLong(this::lotOrder)).toList();
        BigDecimal free=lots.stream().map(l->num(l,"shares").subtract(num(l,"frozenShares"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(free.compareTo(left)<0){block(r,conversion?"CONVERSION":"ORDER",id(order,"id"),aid,"原申请份额在原有效时间不足",left.subtract(free));return allocations;}
        for(var limit:sourceLimits.entrySet()){BigDecimal cardFree=lots.stream().filter(l->id(l,"sourceCardId")==limit.getKey()).map(l->num(l,"shares").subtract(num(l,"frozenShares"))).reduce(BigDecimal.ZERO,BigDecimal::add);if(cardFree.compareTo(limit.getValue())<0){block(r,conversion?"CONVERSION":"ORDER",id(order,"id"),aid,"原来源卡份额不足，不能跨来源分配",limit.getValue().subtract(cardFree));return allocations;}}
        for(var lot:lots){if(left.signum()==0)break;BigDecimal take=left.min(num(lot,"shares").subtract(num(lot,"frozenShares"))).min(sourceLimits.getOrDefault(id(lot,"sourceCardId"),BigDecimal.ZERO));if(take.signum()==0)continue;var a=new LinkedHashMap<String,Object>();a.put("lotId",lot.get("id"));a.put("shares",take);a.put("cost",Db.money(num(lot,"cost").multiply(take).divide(num(lot,"shares"),12,RoundingMode.HALF_UP)));for(String key:List.of("sourceCardId","fundAccountId","acquiredDate"))a.put(key,lot.get(key));allocations.add(a);addEffect(r,e,lot,BigDecimal.ZERO,take,BigDecimal.ZERO);left=left.subtract(take);sourceLimits.put(id(lot,"sourceCardId"),sourceLimits.get(id(lot,"sourceCardId")).subtract(take));}
        return allocations;
    }
    private void addEffect(Replay r,Map<String,Object> original,Map<String,Object> lot,BigDecimal shares,BigDecimal frozen,BigDecimal cost){
        var e=new LinkedHashMap<>(original);e.put("lotId",lot.get("id"));e.put("fundClassId",lot.get("fundClassId"));e.put("sharesDelta",shares);e.put("frozenDelta",frozen);e.put("costDelta",cost);e.put("effectiveDate",effective(original,r).toString());e.put("operationSequence",sequence(original));r.effects.add(e);
        lot.put("shares",num(lot,"shares").add(shares));lot.put("frozenShares",num(lot,"frozenShares").add(frozen));lot.put("cost",num(lot,"cost").add(cost));if(num(lot,"shares").signum()<0||num(lot,"frozenShares").signum()<0||num(lot,"shares").compareTo(num(lot,"frozenShares"))<0)throw new IllegalStateException("历史份额或冻结不足");
    }
    private void change(Replay r,String type,Map<String,Object> original,Map<String,Object> result,Map<String,Object> effect){var item=new LinkedHashMap<String,Object>();item.put("sourceType",type);item.put("sourceId",original.get("id"));item.put("tradingAccountId",original.get("tradingAccountId"));item.put("effectiveDate",effective(effect,r).toString());item.put("operationSequence",sequence(effect));item.put("before",original);item.put("after",result);r.changes.add(item);}
    private void convert(Map<String,Object> input,Replay r,Map<String,Object> c,List<Map<String,Object>> allocations,Map<String,Object> effect){
        BigDecimal sourceNav=nav(input,id(c,"sourceClassId"),c.get("applicationDate")),targetNav=nav(input,id(c,"targetClassId"),c.get("applicationDate")),total=BigDecimal.ZERO,fees=BigDecimal.ZERO;
        for(var a:allocations){
            BigDecimal gross=num(a,"shares").multiply(sourceNav),fee=FundMath.redemptionFee(num(a,"shares"),sourceNav,ChronoUnit.DAYS.between(date(a.get("acquiredDate")),date(c.get("applicationDate"))),num(c,"redemptionRate")),supplement=AdvancedAmounts.supplement(Db.money(gross.subtract(fee)),num(c,"sourceRate"),num(c,"targetRate")),units=gross.subtract(fee).subtract(supplement).divide(targetNav,4,RoundingMode.HALF_UP);if(units.signum()<=0)throw new IllegalStateException("转换后份额不足");
            String source=c.get("id")+"-"+a.get("lotId");
            var existing=r.lots.values().stream().filter(l->"CONVERSION".equals(l.get("sourceType"))&&source.equals(l.get("sourceId"))).findFirst();Map<String,Object> lot,old;
            if(existing.isPresent()){
                lot=existing.get();old=rows(input,"share_entries").stream().filter(e->id(e,"lotId")==id(lot,"id")&&"ADD".equals(e.get("eventType"))).findFirst().orElseThrow(()->new IllegalStateException("转换目标缺少原入账依据"));
            }else{
                var prototype=r.lots.values().stream().filter(l->"CONVERSION".equals(l.get("sourceType"))&&l.get("sourceId").toString().startsWith(c.get("id")+"-")&&id(l,"sourceCardId")==id(a,"sourceCardId")&&id(l,"id")>0).findFirst().orElseThrow(()->new IllegalStateException("转换来源卡缺少历史目标账户关系"));
                old=new LinkedHashMap<>(rows(input,"share_entries").stream().filter(e->id(e,"lotId")==id(prototype,"id")&&"ADD".equals(e.get("eventType"))).findFirst().orElseThrow(()->new IllegalStateException("转换目标缺少原入账依据")));old.put("sourceId",source);
                lot=new LinkedHashMap<>(prototype);long temporary=-r.lots.size()-1;lot.put("id",temporary);lot.put("sourceId",source);lot.put("shares",BigDecimal.ZERO);lot.put("frozenShares",BigDecimal.ZERO);lot.put("cost",BigDecimal.ZERO);lot.put("replaySequence",Long.MAX_VALUE/2-temporary);r.lots.put(temporary,lot);
            }
            addEffect(r,old,lot,units,BigDecimal.ZERO,num(a,"cost"));total=total.add(units);fees=fees.add(fee).add(supplement);
        }
        var result=new LinkedHashMap<String,Object>();result.put("sourceNav",sourceNav);result.put("targetNav",targetNav);result.put("targetShares",total);result.put("fee",fees);result.put("effectiveDate",c.get("confirmationDate"));r.conversionResults.put(id(c,"id"),result);change(r,"CONVERSION",c,result,effect);
    }
    private void audit(long run,String type,long source,long aid,LocalDate date,long sequence,Object before,Object after){db.insert("INSERT INTO reclearing_items(run_id,source_type,source_id,trading_account_id,effective_date,operation_sequence,before_result,after_result) VALUES(?,?,?,?,?,?,?,?)",run,type,source,aid,date,sequence,json.writeValueAsString(Db.jsonDecimals(before)),json.writeValueAsString(Db.jsonDecimals(after)));}
    private void publish(long run,Map<String,Object> input,Replay r){
        for(var lot:new ArrayList<>(r.lots.values()))if(id(lot,"id")<0){
            long temporary=id(lot,"id");long actual=db.insert("INSERT INTO position_lots(user_id,trading_account_id,fund_class_id,source_card_id,fund_account_id,shares,frozen_shares,cost,acquired_date,source_type,source_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",lot.get("userId"),lot.get("tradingAccountId"),lot.get("fundClassId"),lot.get("sourceCardId"),lot.get("fundAccountId"),lot.get("shares"),lot.get("frozenShares"),lot.get("cost"),lot.get("acquiredDate"),lot.get("sourceType"),lot.get("sourceId"));lot.put("id",actual);r.lots.remove(temporary);r.lots.put(actual,lot);
            for(var e:r.effects)if(id(e,"lotId")==temporary)e.put("lotId",actual);for(var allocations:r.allocations.values())for(var a:allocations)if(id(a,"lotId")==temporary)a.put("lotId",actual);
            for(var entitlement:r.dividendLots.values())for(var item:entitlement)if(id(item,"lotId")==temporary)item.put("lotId",actual);
            for(var target:r.lots.values())if("CONVERSION".equals(target.get("sourceType"))&&target.get("sourceId").toString().endsWith("-"+temporary))target.put("sourceId",target.get("sourceId").toString().substring(0,target.get("sourceId").toString().length()-String.valueOf(temporary).length())+actual);
            for(var e:r.effects)if("CONVERSION".equals(e.get("sourceType"))&&"ADD".equals(e.get("eventType"))&&e.get("sourceId").toString().endsWith("-"+temporary))e.put("sourceId",e.get("sourceId").toString().substring(0,e.get("sourceId").toString().length()-String.valueOf(temporary).length())+actual);
            audit(run,"LOT",actual,id(lot,"tradingAccountId"),date(lot.get("acquiredDate")),actual,Map.of(),lot);
        }
        var originals=new ArrayList<>(rows(input,"share_entries"));Collections.reverse(originals);
        for(var e:originals){
            db.update("UPDATE share_entries SET effect_state='REVERSED' WHERE id=?",e.get("id"));
            db.insert("INSERT INTO share_entries(user_id,trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,shares_delta,frozen_delta,cost_delta,created_at,round_id,effect_state,reversal_of,effective_date,operation_sequence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.get("userId"),e.get("tradingAccountId"),e.get("fundClassId"),e.get("lotId"),e.get("sourceType"),e.get("sourceId"),"REVERSE_"+e.get("eventType"),num(e,"sharesDelta").negate(),num(e,"frozenDelta").negate(),num(e,"costDelta").negate(),accounts.clock(),run,"REVERSAL",e.get("id"),effective(e,r),sequence(e));
        }
        for(var e:r.effects)db.insert("INSERT INTO share_entries(user_id,trading_account_id,fund_class_id,lot_id,source_type,source_id,event_type,shares_delta,frozen_delta,cost_delta,created_at,round_id,effective_date,operation_sequence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",e.get("userId"),e.get("tradingAccountId"),e.get("fundClassId"),e.get("lotId"),e.get("sourceType"),e.get("sourceId"),e.get("eventType"),e.get("sharesDelta"),e.get("frozenDelta"),e.get("costDelta"),e.get("createdAt"),run,e.get("effectiveDate"),e.get("operationSequence"));
        for(var before:rows(input,"position_lots")){var after=r.lots.get(id(before,"id"));audit(run,"LOT",id(before,"id"),id(before,"tradingAccountId"),date(before.get("acquiredDate")),id(before,"id"),before,after);db.update("UPDATE position_lots SET shares=?,frozen_shares=?,cost=? WHERE id=?",after.get("shares"),after.get("frozenShares"),after.get("cost"),before.get("id"));}
        for(var a:r.allocations.entrySet()){
            boolean conversion=a.getKey().startsWith("CONVERSION:");long source=Long.parseLong(a.getKey().substring(a.getKey().indexOf(':')+1));String table=conversion?"conversion_allocations":"order_lot_allocations",key=conversion?"conversion_id":"order_id";
            var before=db.rows("SELECT * FROM "+table+" WHERE "+key+"=? ORDER BY id",source);var application=(conversion?r.conversions:r.orders).get(source);
            audit(run,conversion?"CONVERSION_ALLOCATION":"ORDER_ALLOCATION",source,id(application,"tradingAccountId"),date(application.get("applicationDate")),source,before,a.getValue());
            db.update("DELETE FROM "+table+" WHERE "+key+"=?",source);
            for(var row:a.getValue())db.insert("INSERT INTO "+table+"("+key+",lot_id,shares,cost,source_card_id,fund_account_id,acquired_date) VALUES(?,?,?,?,?,?,?)",source,row.get("lotId"),row.get("shares"),row.get("cost"),row.get("sourceCardId"),row.get("fundAccountId"),row.get("acquiredDate"));
        }
        for(var change:r.changes){
            if("DIVIDEND".equals(change.get("sourceType"))){
                long source=id(change,"sourceId");var after=r.rights.get(source);var before=(Map<String,Object>)change.get("before");
                db.update("UPDATE dividend_entitlements SET shares=?,amount=? WHERE id=?",after.get("shares"),after.get("amount"),source);
                var oldLots=rows(input,"dividendLots").stream().filter(l->id(l,"entitlementId")==source).toList();audit(run,"DIVIDEND_LOTS",source,id(change,"tradingAccountId"),date(change.get("effectiveDate")),id(change,"operationSequence"),oldLots,r.dividendLots.get(source));
                db.update("DELETE FROM dividend_lot_entitlements WHERE entitlement_id=?",source);for(var lot:r.dividendLots.get(source))db.update("INSERT INTO dividend_lot_entitlements(entitlement_id,lot_id,shares,amount) VALUES(?,?,?,?)",source,lot.get("lotId"),lot.get("shares"),lot.get("amount"));
                continue;
            }
            if("MONEY_INCOME".equals(change.get("sourceType"))){
                long source=id(change,"sourceId");var after=r.incomes.get(source);var before=(Map<String,Object>)change.get("before");
                db.update("UPDATE money_income_snapshots SET shares=?,income=?,applied_shares=?,cash_adjustment=?,tail=? WHERE id=?",after.get("shares"),after.get("income"),after.get("appliedShares"),after.get("cashAdjustment"),after.get("tail"),source);
                var oldExits=rows(input,"money_exit_disposals").stream().filter(x->id(x,"snapshotId")==source).toList();var exit=r.exits.get(source);
                if(exit!=null){
                    if(oldExits.isEmpty())db.insert("INSERT INTO money_exit_disposals(snapshot_id,user_id,trading_account_id,fund_class_id,source_card_id,carried_tail,original_tail,settled_amount,payout_id,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,'重清按原来源退出结算',?)",source,after.get("userId"),after.get("tradingAccountId"),after.get("fundClassId"),after.get("sourceCardId"),exit.get("carriedTail"),exit.get("originalTail"),exit.get("settledAmount"),after.get("payoutId"),accounts.clock());
                    else db.update("UPDATE money_exit_disposals SET carried_tail=?,original_tail=?,settled_amount=? WHERE snapshot_id=?",exit.get("carriedTail"),exit.get("originalTail"),exit.get("settledAmount"),source);
                }else if(!oldExits.isEmpty())db.update("DELETE FROM money_exit_disposals WHERE snapshot_id=?",source);
                continue;
            }
            boolean conversion="CONVERSION".equals(change.get("sourceType"));long source=id(change,"sourceId");var before=(conversion?r.conversions:r.orders).get(source);var result=(conversion?r.conversionResults:r.results).get(source);
            confirmations.reverse(id(before,"currentConfirmationId"),run);
            if(conversion)db.update("UPDATE conversions SET source_nav=?,target_nav=?,target_shares=?,fee=? WHERE id=?",result.get("sourceNav"),result.get("targetNav"),result.get("targetShares"),result.get("fee"),source);
            else db.update("UPDATE orders SET nav=?,fee=?,net_amount=?,shares=?,rounding_difference=? WHERE id=?",result.get("nav"),result.get("fee"),result.get("netAmount"),result.get("shares"),result.get("roundingDifference"),source);
            var after=db.one("SELECT * FROM "+(conversion?"conversions":"orders")+" WHERE id=?",source);
            confirmations.record(change.get("sourceType").toString(),source,run,funds.nextOpen(accounts.clock().toLocalDate()),date(change.get("effectiveDate")),after);
            audit(run,change.get("sourceType").toString(),source,id(change,"tradingAccountId"),date(change.get("effectiveDate")),id(change,"operationSequence"),before,after);
        }
        for(var old:rows(input,"payouts")){
            BigDecimal amount=r.payouts.get(id(old,"id"));if(amount==null)continue;
            db.update("UPDATE payouts SET status='REVERSED' WHERE id=?",old.get("id"));
            long pid=db.insert("INSERT INTO payouts(user_id,trading_account_id,source_card_id,target_card_id,target_binding_version,authorization_id,source_type,source_id,amount,due_date,status,reason,paid_at,created_at,round_id,previous_payout_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",old.get("userId"),old.get("tradingAccountId"),old.get("sourceCardId"),old.get("targetCardId"),old.get("targetBindingVersion"),old.get("authorizationId"),old.get("sourceType"),old.get("sourceId"),amount,old.get("dueDate"),old.get("status"),old.get("reason"),old.get("paidAt"),old.get("createdAt"),run,old.get("id"));
            if("DIVIDEND".equals(old.get("sourceType")))db.update("UPDATE dividend_entitlements SET payout_id=? WHERE id=?",pid,old.get("sourceId"));
            if("MONEY_EXIT_INCOME".equals(old.get("sourceType"))){db.update("UPDATE money_income_snapshots SET payout_id=? WHERE id=?",pid,old.get("sourceId"));db.update("UPDATE money_exit_disposals SET payout_id=? WHERE snapshot_id=?",pid,old.get("sourceId"));}
            if("PAID".equals(old.get("status"))){
                var credits=db.rows("SELECT * FROM cash_entries WHERE source_type='PAYOUT' AND source_id=? AND event_type='CREDIT' ORDER BY id DESC",old.get("id").toString());if(credits.isEmpty())throw ApiException.conflict("原已付款缺少可核验现金流水");var credit=credits.getFirst();LocalDate effective=credit.get("effectiveDate")==null?date(credit.get("createdAt")):date(credit.get("effectiveDate"));
                cash(run,id(old,"userId"),id(old,"tradingAccountId"),old.get("id").toString(),"REVERSE_CREDIT",num(old,"amount").negate(),effective,id(credit,"id"));
                cash(run,id(old,"userId"),id(old,"tradingAccountId"),String.valueOf(pid),"CREDIT",amount,effective,null);
            }
            audit(run,"PAYOUT",id(old,"id"),id(old,"tradingAccountId"),date(old.get("dueDate")),id(old,"id"),old,db.one("SELECT * FROM payouts WHERE id=?",pid));
        }
        for(var after:r.incomes.values())if(num(after,"cashAdjustment").signum()>0&&after.get("payoutId")==null){long sid=id(after,"id");long pid=trades.payout(id(after,"userId"),id(after,"tradingAccountId"),id(after,"sourceCardId"),"MONEY_EXIT_INCOME",String.valueOf(sid),num(after,"cashAdjustment"),date(after.get("date")).plusDays(1));db.update("UPDATE payouts SET round_id=? WHERE id=?",run,pid);db.update("UPDATE money_income_snapshots SET payout_id=? WHERE id=?",pid,sid);db.update("UPDATE money_exit_disposals SET payout_id=? WHERE snapshot_id=?",pid,sid);audit(run,"PAYOUT_NEW",pid,id(after,"tradingAccountId"),date(after.get("date")),sid,Map.of(),db.one("SELECT * FROM payouts WHERE id=?",pid));}
        for(var effect:r.directCash){
            var old=(Map<String,Object>)effect.get("oldEntry");long sid=id(effect,"id");LocalDate day=date(effect.get("date"));
            if(old!=null&&num(effect,"cashAdjustment").signum()<0)db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at,round_id,reversal_of,effective_date) VALUES(?,?,?,?,'REVERSE_LOSS_DEBIT',?,0,?,?,?,?,?)",effect.get("userId"),effect.get("tradingAccountId"),old.get("sourceType"),String.valueOf(sid),num(old,"availableDelta").negate(),num(old,"availableDelta").negate(),accounts.clock(),run,old.get("id"),day);
            BigDecimal amount=num(effect,"amount");if(amount.signum()<0)db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at,round_id,effective_date) VALUES(?,?,?,?,'LOSS_DEBIT',?,0,?,?,?,?)",effect.get("userId"),effect.get("tradingAccountId"),effect.get("sourceType"),String.valueOf(sid),amount,amount,accounts.clock(),run,day);
        }
        for(var change:r.changes){
            long source=id(change,"sourceId");String type=change.get("sourceType").toString();
            if("DIVIDEND".equals(type))audit(run,type,source,id(change,"tradingAccountId"),date(change.get("effectiveDate")),id(change,"operationSequence"),change.get("before"),db.one("SELECT * FROM dividend_entitlements WHERE id=?",source));
            if("MONEY_INCOME".equals(type)){
                audit(run,type,source,id(change,"tradingAccountId"),date(change.get("effectiveDate")),id(change,"operationSequence"),change.get("before"),db.one("SELECT * FROM money_income_snapshots WHERE id=?",source));
                var old=rows(input,"money_exit_disposals").stream().filter(x->id(x,"snapshotId")==source).toList();var after=db.rows("SELECT * FROM money_exit_disposals WHERE snapshot_id=?",source);if(!old.isEmpty()||!after.isEmpty())audit(run,"MONEY_EXIT",source,id(change,"tradingAccountId"),date(change.get("effectiveDate")),id(change,"operationSequence"),old,after);
            }
        }
        for(var delta:r.cashDelta.entrySet())db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",delta.getValue(),delta.getKey());
        for(var lot:r.lots.values()){
            var sum=db.one("SELECT COALESCE(SUM(shares_delta),0) shares,COALESCE(SUM(frozen_delta),0) frozen,COALESCE(SUM(cost_delta),0) cost FROM share_entries WHERE lot_id=?",lot.get("id"));
            if(num(sum,"shares").compareTo(num(lot,"shares"))!=0||num(sum,"frozen").compareTo(num(lot,"frozenShares"))!=0||num(sum,"cost").compareTo(num(lot,"cost"))!=0)throw ApiException.conflict("重清份额、冻结或成本对账不平，发布已回滚");
        }
        for(var old:rows(input,"accounts")){var wallet=db.one("SELECT * FROM wallets WHERE trading_account_id=?",old.get("id"));var sum=db.one("SELECT COALESCE(SUM(available_delta),0) available,COALESCE(SUM(frozen_delta),0) frozen FROM cash_entries WHERE trading_account_id=?",old.get("id"));if(num(wallet,"available").signum()<0||num(wallet,"available").compareTo(num(old,"available").add(r.cashDelta.getOrDefault(id(old,"id"),BigDecimal.ZERO)))!=0||num(wallet,"frozen").compareTo(num(old,"frozen"))!=0||num(wallet,"available").compareTo(num(sum,"available"))!=0||num(wallet,"frozen").compareTo(num(sum,"frozen"))!=0)throw ApiException.conflict("重清现金对账不平，发布已回滚");}
    }
    private void cash(long run,long uid,long aid,String source,String event,BigDecimal amount,LocalDate effective,Long reversal){db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at,round_id,reversal_of,effective_date) VALUES(?,?,'PAYOUT',?,?,?,0,?,?,?,?,?)",uid,aid,source,event,amount,amount,accounts.clock(),run,reversal,effective);}
}
