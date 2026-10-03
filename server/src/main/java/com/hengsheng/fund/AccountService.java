package com.hengsheng.fund;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    public final Db db;
    private final PasswordEncoder passwords;
    private final ObjectProvider<MoneyIncomeService> moneyIncome;
    private final ObjectProvider<DividendService> dividends;
    public final boolean demo;
    public AccountService(Db db,PasswordEncoder passwords,@Value("${fund.demo:false}") boolean demo,ObjectProvider<MoneyIncomeService> moneyIncome,ObjectProvider<DividendService> dividends) {this.db=db;this.passwords=passwords;this.demo=demo;this.moneyIncome=moneyIncome;this.dividends=dividends;}
    public boolean isDemo() { return demo; }
    public long userId() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||"anonymousUser".equals(auth.getPrincipal()))throw new ApiException(401,"UNAUTHENTICATED","请先登录");
        var user=db.one("SELECT id,enabled FROM users WHERE username=?",auth.getName());
        if(!Db.yes(user.get("enabled")))throw new ApiException(403,"USER_DISABLED","账户已停用");
        return Db.id(user.get("id"));
    }
    public void requireRole(String role) {
        var user=db.one("SELECT role FROM users WHERE id=?",userId());String current=user.get("role").toString();
        if(!current.equals(role)&&!("OPERATOR".equals(role)&&"ADMIN".equals(current)))throw new ApiException(403,"FORBIDDEN","权限不足");
    }
    public LocalDateTime clock() {
        if(demo) {var r=db.rows("SELECT value FROM app_settings WHERE name='demo_clock'");if(!r.isEmpty())return LocalDateTime.parse(r.getFirst().get("value").toString());}
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    }
    public void audit(String action,String objectType,long objectId,String reason) {
        Long actor=null;var a=SecurityContextHolder.getContext().getAuthentication();
        if(a!=null&&a.isAuthenticated()&&!"anonymousUser".equals(a.getPrincipal())){var r=db.rows("SELECT id FROM users WHERE username=?",a.getName());if(!r.isEmpty())actor=Db.id(r.getFirst().get("id"));}
        db.insert("INSERT INTO audit_events(actor_id,action,object_type,object_id,reason,created_at) VALUES(?,?,?,?,?,?)",actor,action,objectType,objectId,reason==null?"":reason,clock());
    }
    public void notifyUser(long userId,String type,String sourceId,String title,String message) {db.update("INSERT IGNORE INTO notifications(user_id,type,source_id,title,message,created_at) VALUES(?,?,?,?,?,?)",userId,type,sourceId,title,message,clock());}
    public Map<String,Object> me() {return db.one("SELECT id,username,display_name,role,risk_level,risk_expires_at,default_trading_account_id,opened,must_change_password FROM users WHERE id=?",userId());}
    @Transactional public Map<String,Object> register(Map<String,Object> input) {
        String username=Db.text(input,"username"),password=Db.text(input,"password"),display=Db.text(input,"displayName");
        if(!username.matches("[A-Za-z0-9_]{3,60}"))throw ApiException.bad("用户名须为3至60位字母、数字或下划线");validatePassword(password);
        if(display.length()>100)throw ApiException.bad("姓名过长");
        if(db.scalar("SELECT COUNT(*) FROM users WHERE username=?",username).signum()>0)throw ApiException.conflict("用户名已存在");
        long id=db.insert("INSERT INTO users(username,password_hash,display_name) VALUES(?,?,?)",username,passwords.encode(password),display);
        return db.one("SELECT id,username,display_name,role,opened FROM users WHERE id=?",id);
    }
    public static void validatePassword(String password) {if(password.length()<8||password.length()>72||!password.matches(".*[A-Za-z].*")||!password.matches(".*[0-9].*"))throw ApiException.bad("密码须为8至72位并含字母与数字");}
    @Transactional public Map<String,Object> open() {long id=userId();db.update("UPDATE users SET opened=TRUE WHERE id=?",id);audit("OPEN","USER",id,"模拟开户");return me();}
    @Transactional public Map<String,Object> risk(Map<String,Object> input) {
        Object raw=input.get("answers");if(!(raw instanceof List<?> answers)||answers.size()!=10)throw ApiException.bad("风险问卷须回答10题");
        int sum=0;for(Object v:answers){int n;try{n=Integer.parseInt(v.toString());}catch(Exception ex){throw ApiException.bad("问卷答案须为1至5");}if(n<1||n>5)throw ApiException.bad("问卷答案须为1至5");sum+=n;}
        int level=Math.max(1,Math.min(5,(sum-10)/8+1));long uid=userId();LocalDate expiry=clock().toLocalDate().plusDays(365);
        db.insert("INSERT INTO risk_assessments(user_id,answers,risk_level,expires_at,created_at) VALUES(?,?,?,?,?)",uid,answers.toString(),level,expiry,clock());
        db.update("UPDATE users SET risk_level=?,risk_expires_at=? WHERE id=?",level,expiry,uid);return me();
    }
    @Transactional public void changePassword(Map<String,Object> input) {
        long uid=userId();var user=db.one("SELECT password_hash FROM users WHERE id=? FOR UPDATE",uid);
        if(!passwords.matches(Db.text(input,"oldPassword"),user.get("passwordHash").toString()))throw ApiException.bad("原密码错误");
        String next=Db.text(input,"newPassword");validatePassword(next);db.update("UPDATE users SET password_hash=?,must_change_password=FALSE WHERE id=?",passwords.encode(next),uid);audit("PASSWORD_CHANGE","USER",uid,"用户修改密码");
    }
    public String cardQuery() {return "SELECT c.id,c.user_id,c.bank_name,c.sim_number,c.holder_name,c.status,c.receive_state,c.single_limit,c.daily_limit,t.id trading_account_id,t.account_no,t.binding_version,t.debit_frozen,t.status account_status,w.available,w.frozen,(u.default_trading_account_id=t.id) is_default FROM bank_cards c JOIN trading_accounts t ON t.bank_card_id=c.id JOIN wallets w ON w.trading_account_id=t.id JOIN users u ON u.id=c.user_id";}
    public List<Map<String,Object>> cards(long uid) {return db.rows(cardQuery()+" WHERE c.user_id=? AND t.id=(SELECT MAX(current_account.id) FROM trading_accounts current_account WHERE current_account.bank_card_id=c.id) ORDER BY c.id",uid);}
    public Map<String,Object> card(long uid,long cardId) {return db.one(cardQuery()+" WHERE c.user_id=? AND c.id=? ORDER BY t.id DESC LIMIT 1",uid,cardId);}
    public Map<String,Object> lockAccount(long uid,long accountId) {return db.one("SELECT t.*,c.status card_status,c.receive_state,c.bank_name,c.holder_name,c.single_limit,c.daily_limit,w.available,w.frozen FROM trading_accounts t JOIN bank_cards c ON c.id=t.bank_card_id JOIN wallets w ON w.trading_account_id=t.id WHERE t.id=? AND t.user_id=? FOR UPDATE",accountId,uid);}
    public void requireDebit(Map<String,Object> account,long cardId,long version) {
        if(!"ACTIVE".equals(account.get("status"))||!"ACTIVE".equals(account.get("cardStatus"))||Db.yes(account.get("debitFrozen")))throw ApiException.conflict("交易账户当前不可扣款");
        if(Db.id(account.get("bankCardId"))!=cardId||Db.id(account.get("bindingVersion"))!=version)throw ApiException.conflict("结算卡绑定已变化，请重新选择账户");
    }
    @Transactional public Map<String,Object> bind(Map<String,Object> input) {
        long uid=userId();String bank=Db.text(input,"bankName"),holder=Db.text(input,"holderName"),key=Db.text(input,"requestKey"),fingerprint=TradeService.fingerprint(input);
        if(bank.length()>80||holder.length()>100||key.length()>100)throw ApiException.bad("输入过长");
        var user=db.one("SELECT * FROM users WHERE id=? FOR UPDATE",uid);
        var prior=db.rows("SELECT t.id,t.bind_fingerprint,c.bank_name,c.holder_name FROM trading_accounts t JOIN card_bindings b ON b.trading_account_id=t.id AND b.binding_version=1 JOIN bank_cards c ON c.id=b.bank_card_id WHERE t.user_id=? AND t.bind_request_key=?",uid,key);
        if(!prior.isEmpty()) {var p=prior.getFirst();if((p.get("bindFingerprint")!=null&&!fingerprint.equals(p.get("bindFingerprint")))||!bank.equals(p.get("bankName"))||!holder.equals(p.get("holderName")))throw ApiException.conflict("请求键已用于不同绑卡请求");return db.one(cardQuery()+" WHERE c.user_id=? AND t.id=?",uid,p.get("id"));}
        if(!Db.yes(user.get("opened")))throw ApiException.conflict("请先模拟开户");
        if(!holder.equals(user.get("displayName")))throw ApiException.bad("模拟持卡人须与账户姓名一致");
        long cid;
        if(input.containsKey("simNumber")){
            var old=db.one("SELECT * FROM bank_cards WHERE sim_number=? AND user_id=? FOR UPDATE",Db.text(input,"simNumber"),uid);cid=Db.id(old.get("id"));
            if(!"UNBOUND".equals(old.get("status"))||db.scalar("SELECT COUNT(*) FROM trading_accounts WHERE bank_card_id=? AND status='ACTIVE'",cid).signum()>0)throw ApiException.conflict("仅可重新绑定本人已解绑且无活跃绑定的模拟卡");
            if(!bank.equals(old.get("bankName"))||!holder.equals(old.get("holderName")))throw ApiException.bad("重绑须保持原模拟银行和持卡人");
            db.update("UPDATE bank_cards SET status='ACTIVE' WHERE id=?",cid);
        }else cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,holder_name,sim_number,request_key) VALUES(?,?,?,?,?)",uid,bank,holder,"SIM-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT),key);
        long tid=db.insert("INSERT INTO trading_accounts(user_id,account_no,bank_card_id,bind_request_key,bind_fingerprint) VALUES(?,?,?,?,?)",uid,"TRA-"+UUID.randomUUID().toString().substring(0,16),cid,key,fingerprint);
        db.update("INSERT INTO wallets(trading_account_id) VALUES(?)",tid);db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version) VALUES(?,?,1)",tid,cid);
        if(!Db.yes(user.get("initialGranted"))) {credit(tid,"INITIAL","USER-"+uid,new BigDecimal("100000.00"));db.update("UPDATE users SET initial_granted=TRUE WHERE id=?",uid);}
        if(user.get("defaultTradingAccountId")==null)db.update("UPDATE users SET default_trading_account_id=? WHERE id=?",tid,uid);
        audit("BIND_CARD","TRADING_ACCOUNT",tid,"创建模拟结算卡");return card(uid,cid);
    }
    @Transactional public void setDefault(long cid) {long uid=userId();var c=card(uid,cid);var a=lockAccount(uid,Db.id(c.get("tradingAccountId")));requireDebit(a,cid,Db.id(a.get("bindingVersion")));db.update("UPDATE users SET default_trading_account_id=? WHERE id=?",a.get("id"),uid);audit("DEFAULT_CARD","BANK_CARD",cid,"设置默认卡");}
    @Transactional public void status(long cid,String status) {
        if(!Set.of("ACTIVE","DISABLED").contains(status))throw ApiException.bad("卡状态只能为ACTIVE或DISABLED");long uid=userId();var c=card(uid,cid);lockAccount(uid,Db.id(c.get("tradingAccountId")));
        if(!Set.of("ACTIVE","DISABLED").contains(c.get("status")))throw ApiException.conflict("该卡已解绑或替换");
        db.update("UPDATE bank_cards SET status=? WHERE id=?",status,cid);
        if("DISABLED".equals(status))db.update("UPDATE users SET default_trading_account_id=NULL WHERE id=? AND default_trading_account_id=?",uid,c.get("tradingAccountId"));
        audit("CARD_STATUS","BANK_CARD",cid,status);
    }
    @Transactional public void unbind(long cid,Map<String,Object> input) {
        long uid=userId();var c=card(uid,cid);long tid=Db.id(c.get("tradingAccountId"));var a=lockAccount(uid,tid);
        if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭");
        if(Db.yes(a.get("debitFrozen"))||db.scalar("SELECT COUNT(*) FROM card_change_requests WHERE trading_account_id=? AND status='PENDING'",tid).signum()>0)throw ApiException.conflict("账户存在待处理换卡或扣款冻结，不能解绑");
        if(Db.decimal(a.get("available")).signum()!=0||Db.decimal(a.get("frozen")).signum()!=0||db.scalar("SELECT COALESCE(SUM(shares),0) FROM position_lots WHERE trading_account_id=?",tid).signum()!=0||db.scalar("SELECT COUNT(*) FROM orders WHERE trading_account_id=? AND status IN('SUBMITTED','CONFIRMED')",tid).signum()>0||db.scalar("SELECT COUNT(*) FROM payouts WHERE trading_account_id=? AND status!='PAID'",tid).signum()>0)throw ApiException.conflict("账户仍有资产或未完成业务，不能解绑");
        String[][] blockers={{"sip_plans","status IN('ACTIVE','PAUSED')"},{"conversions","status='SUBMITTED'"},{"offering_orders","status IN('SUBMITTED','REFUND_PENDING')"},{"dividend_entitlements","status='PENDING'"},{"money_income_snapshots","(status='PENDING' OR (tail<>0 AND date=(SELECT MAX(m2.date) FROM money_income_snapshots m2 WHERE m2.trading_account_id=money_income_snapshots.trading_account_id AND m2.fund_class_id=money_income_snapshots.fund_class_id AND m2.source_card_id=money_income_snapshots.source_card_id)))"}};
        for(String[] b:blockers)if(db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",b[0]).signum()>0&&db.scalar("SELECT COUNT(*) FROM "+b[0]+" WHERE trading_account_id=? AND "+b[1],tid).signum()>0)throw ApiException.conflict("账户仍有定投、分红、转换或收益尾差，不能解绑");
        var incomes=moneyIncome.getIfAvailable();
        if(incomes!=null && incomes.hasUnsettledForAccount(tid)) throw ApiException.conflict("货币基金末期收益尚未结算，请先执行收益与付款批次");
        var dividendService=dividends.getIfAvailable();
        if(dividendService!=null && dividendService.hasUnregisteredRightsForAccount(tid)) throw ApiException.conflict("仍有待登记的分红权益，请先处理分红快照与支付");
        var user=db.one("SELECT default_trading_account_id FROM users WHERE id=? FOR UPDATE",uid);
        if(user.get("defaultTradingAccountId")!=null&&Db.id(user.get("defaultTradingAccountId"))==tid){
            var usable=db.rows("SELECT t.id FROM trading_accounts t JOIN bank_cards c ON c.id=t.bank_card_id WHERE t.user_id=? AND t.id<>? AND t.status='ACTIVE' AND c.status='ACTIVE' AND t.debit_frozen=FALSE",uid,tid);
            if(!usable.isEmpty()&&!input.containsKey("replacementTradingAccountId"))throw ApiException.conflict("请先将另一张可扣款卡设为默认卡，再解绑");
            if(input.containsKey("replacementTradingAccountId")){long next=Db.id(input.get("replacementTradingAccountId"));var target=lockAccount(uid,next);requireDebit(target,Db.id(target.get("bankCardId")),Db.id(target.get("bindingVersion")));if(next==tid)throw ApiException.bad("默认账户不能仍是待解绑账户");db.update("UPDATE users SET default_trading_account_id=? WHERE id=?",next,uid);}else db.update("UPDATE users SET default_trading_account_id=NULL WHERE id=?",uid);
        }
        db.update("UPDATE trading_accounts SET status='CLOSED' WHERE id=?",tid);db.update("UPDATE bank_cards SET status='UNBOUND' WHERE id=?",cid);audit("UNBIND_CARD","BANK_CARD",cid,"零资产解绑");
    }
    @Transactional public long linkFundAccount(long uid,long tid,long fid) {
        long registrar=Db.id(db.one("SELECT registrar_id FROM fund_classes WHERE id=? FOR UPDATE",fid).get("registrarId"));
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        var a=lockAccount(uid,tid);if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭");
        var existing=db.rows("SELECT id FROM fund_accounts WHERE user_id=? AND registrar_id=?",uid,registrar);
        long fa=existing.isEmpty()?db.insert("INSERT INTO fund_accounts(user_id,registrar_id,account_no) VALUES(?,?,?)",uid,registrar,"TA-"+UUID.randomUUID().toString().substring(0,16)):Db.id(existing.getFirst().get("id"));
        db.update("INSERT IGNORE INTO trading_fund_accounts(trading_account_id,fund_account_id) VALUES(?,?)",tid,fa);return fa;
    }
    @Transactional public void credit(long tid,String sourceType,String sourceId,BigDecimal amount) {
        amount=Db.money(amount);db.one("SELECT * FROM wallets WHERE trading_account_id=? FOR UPDATE",tid);
        if(db.scalar("SELECT COUNT(*) FROM cash_entries WHERE trading_account_id=? AND source_type=? AND source_id=? AND event_type='CREDIT'",tid,sourceType,sourceId).signum()>0)return;
        long uid=Db.id(db.one("SELECT user_id FROM trading_accounts WHERE id=?",tid).get("userId"));
        db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",amount,tid);cash(uid,tid,sourceType,sourceId,"CREDIT",amount,BigDecimal.ZERO);
    }
    public void cash(long uid,long tid,String sourceType,String sourceId,String event,BigDecimal availableDelta,BigDecimal frozenDelta) {db.insert("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,?,?,?,?,?,?,?)",uid,tid,sourceType,sourceId,event,availableDelta,frozenDelta,availableDelta.add(frozenDelta),clock());}
    @Transactional public Map<String,Object> cashOperation(long tid,Map<String,Object> input,boolean topUp) {
        long uid=userId();var a=lockAccount(uid,tid);if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭");
        BigDecimal amount=Db.positive(input,"amount",2);String key=Db.text(input,"requestKey"),source=topUp?"TOPUP":"WITHDRAW";
        var prior=db.rows("SELECT amount FROM cash_entries WHERE trading_account_id=? AND source_type=? AND source_id=?",tid,source,key);
        if(!prior.isEmpty()){if(Db.decimal(prior.getFirst().get("amount")).abs().compareTo(amount)!=0)throw ApiException.conflict("请求键已用于不同金额");return db.one("SELECT * FROM wallets WHERE trading_account_id=?",tid);}
        if(topUp)credit(tid,source,key,amount);else {if(Db.decimal(a.get("available")).compareTo(amount)<0)throw ApiException.conflict("该交易账户可用余额不足");db.update("UPDATE wallets SET available=available-? WHERE trading_account_id=?",amount,tid);cash(uid,tid,source,key,"WITHDRAW",amount.negate(),BigDecimal.ZERO);}
        return db.one("SELECT * FROM wallets WHERE trading_account_id=?",tid);
    }
}
