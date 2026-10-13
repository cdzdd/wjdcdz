package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class FundService {
    private final Db db;private final AccountService account;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public FundService(Db db,AccountService account,org.springframework.transaction.PlatformTransactionManager manager){this.db=db;this.account=account;this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);}
    public List<Map<String,Object>> funds() {return db.rows("SELECT f.*,COALESCE((SELECT n.nav FROM nav_records n WHERE n.fund_class_id=f.id AND n.published=TRUE AND n.date<=? ORDER BY n.date DESC LIMIT 1),IF(f.type='MONEY',1,NULL)) nav,(SELECT n.date FROM nav_records n WHERE n.fund_class_id=f.id AND n.published=TRUE AND n.date<=? ORDER BY n.date DESC LIMIT 1) nav_date FROM fund_classes f ORDER BY f.id",account.clock().toLocalDate(),account.clock().toLocalDate());}
    public Map<String,Object> fund(long id) {var f=funds().stream().filter(v->Db.id(v.get("id"))==id).findFirst().orElseThrow(ApiException::missing);f.put("feeTiers",db.rows("SELECT min_amount,rate,fixed_fee FROM fee_tiers WHERE fund_class_id=? ORDER BY min_amount",id));return f;}
    public LocalDate applicationDate(long fid,LocalDateTime time) {
        var f=db.one("SELECT cutoff_time FROM fund_classes WHERE id=?",fid);LocalDate d=time.toLocalDate();LocalTime cutoff=LocalTime.parse(f.get("cutoffTime").toString());
        if(!time.toLocalTime().isBefore(cutoff))d=d.plusDays(1);
        return nextOpen(d);
    }
    public LocalDate nextOpen(LocalDate start) {LocalDate d=start;for(int i=0;i<370;i++,d=d.plusDays(1)){var cal=db.rows("SELECT open FROM trading_calendar WHERE date=?",d);if(cal.isEmpty())throw ApiException.conflict("缺少交易日历："+d);if(Db.yes(cal.getFirst().get("open")))return d;}throw ApiException.conflict("交易日历未包含下个开放日");}
    public LocalDate addTradingDays(LocalDate start,int count) {LocalDate d=start;for(int i=0;i<count;i++)d=nextOpen(d.plusDays(1));return d;}
    public Optional<BigDecimal> nav(long fid,LocalDate date) {var f=db.one("SELECT type FROM fund_classes WHERE id=?",fid);if("MONEY".equals(f.get("type")))return Optional.of(new BigDecimal("1.000000"));var n=db.rows("SELECT nav FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",fid,date);return n.isEmpty()?Optional.empty():Optional.of(Db.decimal(n.getFirst().get("nav")));}
    public void eligibility(long uid,Map<String,Object> f) {
        var u=db.one("SELECT enabled,opened,risk_level,risk_expires_at FROM users WHERE id=?",uid);
        if(!Db.yes(u.get("enabled"))||!Db.yes(u.get("opened")))throw ApiException.conflict("账户未开户或已停用");
        if(u.get("riskExpiresAt")==null||LocalDate.parse(u.get("riskExpiresAt").toString()).isBefore(account.clock().toLocalDate()))throw ApiException.conflict("风险测评尚未完成或已过期");
        if(Db.id(u.get("riskLevel"))<Db.id(f.get("riskLevel")))throw ApiException.conflict("基金风险等级高于测评等级");
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> saveFund(Long id,Map<String,Object> in) {
        account.requireRole("OPERATOR");Map<String,Object> original=id==null?Map.of():db.one("SELECT * FROM fund_classes WHERE id=? FOR UPDATE",id);
        Map<String,Object> f=new HashMap<>(original);String oldState=id==null?null:f.get("state").toString();f.putAll(in);
        String code=Db.text(f,"code"),name=Db.text(f,"name"),type=Db.text(f,"type"),manager=Db.text(f,"manager"),state=Db.text(f,"state");
        if(!Set.of("STOCK","MIXED","BOND","MONEY").contains(type)||!Set.of("DRAFT","RAISING","BUILDING","OPEN","PAUSED","TERMINATED","LIQUIDATED","FAILED").contains(state))throw ApiException.bad("基金类型或状态无效");
        if(oldState!=null&&Set.of("TERMINATED","LIQUIDATED","FAILED").contains(oldState)&&!oldState.equals(state))throw ApiException.conflict("基金终态不能通过普通编辑恢复");
        if(Set.of("TERMINATED","LIQUIDATED","FAILED").contains(state)&&!state.equals(oldState))throw ApiException.conflict("请使用募集结束、终止或清盘专用接口");
        if(oldState!=null&&!state.equals(oldState)&&!(oldState.equals("DRAFT")&&state.equals("RAISING"))&&!(oldState.equals("BUILDING")&&state.equals("OPEN")))throw ApiException.conflict("该状态变更须通过募集或终止等专用业务接口");
        if(id!=null&&(!code.equals(original.get("code"))||!type.equals(original.get("type"))||!manager.equals(original.get("manager"))||Db.id(f.get("registrarId"))!=Db.id(original.get("registrarId")))&&hasBusinessHistory(id))throw ApiException.conflict("产品已有业务历史，不能修改代码、类型、登记机构或管理人");
        int risk=Integer.parseInt(Db.text(f,"riskLevel"));if(risk<1||risk>5)throw ApiException.bad("风险等级须为1至5");
        BigDecimal pr=Db.decimal(f.getOrDefault("purchaseRate","0")),rr=Db.decimal(f.getOrDefault("redemptionRate","0"));if(pr.signum()<0||rr.signum()<0||pr.compareTo(BigDecimal.ONE)>0||rr.compareTo(BigDecimal.ONE)>0)throw ApiException.bad("费率须在0至1之间");
        BigDecimal minPurchase=Db.decimal(f.getOrDefault("minPurchase","10")),minRedemption=Db.decimal(f.getOrDefault("minRedemption","1"));if(minPurchase.signum()<=0||minPurchase.scale()>2||minRedemption.signum()<=0||minRedemption.scale()>4)throw ApiException.bad("最低申购金额与最低赎回份额须为精度合法的正数");
        LocalTime cutoff=LocalTime.parse(f.getOrDefault("cutoffTime","15:00:00").toString());int confirmationDays=Integer.parseInt(f.getOrDefault("confirmationDays",1).toString()),paymentDays=Integer.parseInt(f.getOrDefault("paymentDays",3).toString());if(confirmationDays<1||confirmationDays>30||paymentDays<confirmationDays||paymentDays>60)throw ApiException.bad("确认与付款交易日参数无效");
        if(id==null)id=db.insert("INSERT INTO fund_classes(code,name,type,manager,registrar_id,risk_level,state,purchase_rate,redemption_rate,min_purchase,min_redemption,purchase_open,redemption_open) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",code,name,type,manager,Db.id(f.get("registrarId")),risk,state,pr,rr,Db.decimal(f.getOrDefault("minPurchase","10")),Db.decimal(f.getOrDefault("minRedemption","1")),f.getOrDefault("purchaseOpen",true),f.getOrDefault("redemptionOpen",true));
        else db.update("UPDATE fund_classes SET code=?,name=?,type=?,manager=?,registrar_id=?,risk_level=?,state=?,purchase_rate=?,redemption_rate=?,min_purchase=?,min_redemption=?,purchase_open=?,redemption_open=?,rule_version=rule_version+1 WHERE id=?",code,name,type,manager,Db.id(f.get("registrarId")),risk,state,pr,rr,Db.decimal(f.get("minPurchase")),Db.decimal(f.get("minRedemption")),f.get("purchaseOpen"),f.get("redemptionOpen"),id);
        db.update("UPDATE fund_classes SET cutoff_time=?,confirmation_days=?,payment_days=? WHERE id=?",cutoff,confirmationDays,paymentDays,id);
        account.audit("FUND_SAVE","FUND",id,state);return fund(id);
    }
    private boolean hasBusinessHistory(long fid){return db.scalar("""
        SELECT IF(EXISTS(SELECT 1 FROM orders WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM position_lots WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM money_income_snapshots WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM conversions WHERE source_class_id=? OR target_class_id=?)
          OR EXISTS(SELECT 1 FROM sip_plans WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM offering_events WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM dividend_events WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM liquidation_events WHERE fund_class_id=?)
          OR EXISTS(SELECT 1 FROM conversion_pairs WHERE source_class_id=? OR target_class_id=?),1,0)
        """,fid,fid,fid,fid,fid,fid,fid,fid,fid,fid,fid).signum()>0;}
    @Transactional public Map<String,Object> saveNav(Map<String,Object> in) {
        account.requireRole("OPERATOR");long fid=Db.id(in.get("fundClassId"));LocalDate date=LocalDate.parse(Db.text(in,"date"));BigDecimal nav=Db.positive(in,"nav",6);
        var product=db.one("SELECT id,type FROM fund_classes WHERE id=?",fid);if("MONEY".equals(product.get("type"))&&nav.compareTo(BigDecimal.ONE)!=0)throw ApiException.bad("货币基金净值固定为1，请通过万份收益登记收益变化");
        var cal=db.one("SELECT open FROM trading_calendar WHERE date=?",date);if(!Db.yes(cal.get("open")))throw ApiException.bad("净值只能属于开放日");
        if(account.clock().isBefore(date.atTime(15,10)))throw ApiException.conflict("净值最早在所属开放日15:10发布");
        var old=db.rows("SELECT id,used,published FROM nav_records WHERE fund_class_id=? AND date=? FOR UPDATE",fid,date);
        if(!old.isEmpty()&&(Db.yes(old.getFirst().get("used"))||Db.yes(old.getFirst().get("published"))))throw ApiException.conflict("已发布或用于成交的净值不可覆盖");
        if(old.isEmpty())db.insert("INSERT INTO nav_records(fund_class_id,date,nav,published) VALUES(?,?,?,TRUE)",fid,date,nav);else db.update("UPDATE nav_records SET nav=?,published=TRUE WHERE id=?",nav,old.getFirst().get("id"));
        account.audit("NAV_PUBLISH","FUND",fid,date.toString());return db.one("SELECT * FROM nav_records WHERE fund_class_id=? AND date=?",fid,date);
    }
    public Map<String,Object> market(LocalDate date) {
        int added=0,failed=0;var details=new ArrayList<String>();LocalDate today=account.clock().toLocalDate(),end=date.isBefore(today)?date:today;
        LocalDate dateCursor=LocalDate.of(1000,1,1);
        while(true){
            var days=db.rows("SELECT date FROM trading_calendar WHERE open=TRUE AND date>? AND date<=? ORDER BY date LIMIT 200",dateCursor,end);
            if(days.isEmpty())break;
            for(var cal:days){
                LocalDate day=LocalDate.parse(cal.get("date").toString());dateCursor=day;
                if(account.clock().isBefore(day.atTime(15,10)))continue;
                long cursor=0;
                while(true){
                    var candidates=db.rows("SELECT f.id FROM fund_classes f WHERE f.id>? AND "+marketEligibility()+" ORDER BY f.id LIMIT 200",cursor,day,day,day);
                    if(candidates.isEmpty())break;
                    for(var candidate:candidates){
                        long fid=Db.id(candidate.get("id"));cursor=fid;
                        try{added+=transactions.execute(status->marketOne(fid,day));}
                        catch(RuntimeException ex){failed++;details.add("行情产品"+fid+"/"+day+"："+Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName()));}
                    }
                }
            }
        }
        return Map.of("processed",added,"waiting",0,"failed",failed,"details",details);
    }
    private String marketEligibility(){return "(f.state='OPEN' OR (f.state='TERMINATED' AND DATE(f.terminated_at)>=? AND (EXISTS(SELECT 1 FROM orders o WHERE o.fund_class_id=f.id AND o.status='SUBMITTED' AND o.application_date=?) OR EXISTS(SELECT 1 FROM conversions c WHERE (c.source_class_id=f.id OR c.target_class_id=f.id) AND c.status='SUBMITTED' AND c.application_date=?))))";}
    private int marketOne(long fid,LocalDate day){
        var rows=db.rows("SELECT f.id,f.code,f.type FROM fund_classes f WHERE f.id=? AND "+marketEligibility()+" FOR UPDATE",fid,day,day,day);
        if(rows.isEmpty()||db.scalar("SELECT COUNT(*) FROM nav_records WHERE fund_class_id=? AND date=?",fid,day).signum()>0)return 0;
        var fund=rows.getFirst();BigDecimal price="MONEY".equals(fund.get("type"))?new BigDecimal("1.000000"):new BigDecimal("1.0").add(new BigDecimal(Math.floorMod(Objects.hash(fund.get("code"),day),2500)).movePointLeft(4)).setScale(6);
        if(!"MONEY".equals(fund.get("type")))price=price.subtract(db.scalar("SELECT COALESCE(SUM(per_share),0) FROM dividend_events WHERE fund_class_id=? AND ex_date<=?",fid,day));
        if(price.signum()<=0)throw ApiException.conflict("模拟除息净值非正，请管理员检查分红参数");
        return db.update("INSERT IGNORE INTO nav_records(fund_class_id,date,nav,published) VALUES(?,?,?,TRUE)",fid,day,price);
    }
}
