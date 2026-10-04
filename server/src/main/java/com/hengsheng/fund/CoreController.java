package com.hengsheng.fund;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class CoreController {
    private final Db db;private final AccountService account;private final FundService funds;private final TradeService trades;private final PortfolioService portfolio;private final CardChangeService changes;
    private final PasswordEncoder passwords;private final UserDetailsService users;private final ObjectProvider<AdvancedService> advanced;
    private record Attempts(long start,int failures) {}
    private final Map<String,Attempts> attempts=new ConcurrentHashMap<>();
    public CoreController(Db db,AccountService account,FundService funds,TradeService trades,PortfolioService portfolio,CardChangeService changes,PasswordEncoder passwords,UserDetailsService users,ObjectProvider<AdvancedService> advanced){this.db=db;this.account=account;this.funds=funds;this.trades=trades;this.portfolio=portfolio;this.changes=changes;this.passwords=passwords;this.users=users;this.advanced=advanced;}
    @GetMapping("/auth/csrf") public Map<String,Object> csrf(HttpServletRequest request){var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @PostMapping("/auth/register") public Map<String,Object> register(@RequestBody Map<String,Object> input){return account.register(input);}
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Map<String,Object> input,HttpServletRequest request,HttpServletResponse response){
        String username=Db.text(input,"username"),password=Db.text(input,"password"),key=request.getRemoteAddr()+"/"+username;long now=System.currentTimeMillis();var prior=attempts.get(key);
        if(prior!=null&&now-prior.start()<60000&&prior.failures()>=10)throw new ApiException(429,"LOGIN_RATE_LIMIT","登录尝试过多，请稍后重试");
        var rows=db.rows("SELECT password_hash,enabled FROM users WHERE username=?",username);
        if(rows.isEmpty()||!Db.yes(rows.getFirst().get("enabled"))||!passwords.matches(password,rows.getFirst().get("passwordHash").toString())){attempts.compute(key,(k,p)->p==null||now-p.start()>=60000?new Attempts(now,1):new Attempts(p.start(),p.failures()+1));throw new ApiException(401,"BAD_CREDENTIALS","用户名、密码错误或账户已停用");}
        attempts.remove(key);var principal=users.loadUserByUsername(username);var auth=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);
        request.getSession(true);request.changeSessionId();new HttpSessionSecurityContextRepository().saveContext(context,request,response);new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
        return account.me();
    }
    @PostMapping("/auth/logout") public Map<String,Object> logout(HttpServletRequest request){var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();return Map.of("loggedOut",true);}
    @GetMapping("/me") public Map<String,Object> me(){return account.me();}
    @PostMapping("/account/open") public Map<String,Object> open(){return account.open();}
    @PostMapping("/risk-assessments") public Map<String,Object> risk(@RequestBody Map<String,Object> input){return account.risk(input);}
    @PostMapping("/account/password") public Map<String,Object> password(@RequestBody Map<String,Object> input){account.changePassword(input);return Map.of("changed",true);}
    @GetMapping("/funds") public List<Map<String,Object>> funds(){account.userId();return funds.funds();}
    @GetMapping("/registrars") public List<Map<String,Object>> registrars(){account.userId();return db.rows("SELECT * FROM registrars ORDER BY id");}
    @GetMapping("/fund-classes/{id}") public Map<String,Object> fund(@PathVariable long id){account.userId();return funds.fund(id);}
    @GetMapping("/fund-classes/{id}/history") public List<Map<String,Object>> history(@PathVariable long id){account.userId();funds.fund(id);if("MONEY".equals(funds.fund(id).get("type")))return db.rows("SELECT date,'1.000000' nav,income_per_ten_thousand,annual_yield FROM money_income_records WHERE fund_class_id=? ORDER BY date",id);return db.rows("SELECT date,nav FROM nav_records WHERE fund_class_id=? AND published=TRUE ORDER BY date",id);}
    @GetMapping("/favorites") public List<Long> favorites(){return db.rows("SELECT fund_class_id FROM favorites WHERE user_id=? ORDER BY fund_class_id",account.userId()).stream().map(r->Db.id(r.get("fundClassId"))).toList();}
    @PutMapping("/favorites/{id}") public Map<String,Object> favorite(@PathVariable long id){long uid=account.userId();funds.fund(id);db.update("INSERT IGNORE INTO favorites(user_id,fund_class_id) VALUES(?,?)",uid,id);return Map.of("saved",true);}
    @DeleteMapping("/favorites/{id}") public Map<String,Object> unfavorite(@PathVariable long id){db.update("DELETE FROM favorites WHERE user_id=? AND fund_class_id=?",account.userId(),id);return Map.of("saved",false);}
    @GetMapping({"/bank-cards","/trading-accounts"}) public List<Map<String,Object>> cards(){return account.cards(account.userId());}
    @PostMapping("/bank-cards") public Map<String,Object> bind(@RequestBody Map<String,Object> input){return account.bind(input);}
    @PutMapping("/bank-cards/{id}/default") public Map<String,Object> setDefault(@PathVariable long id){account.setDefault(id);return account.card(account.userId(),id);}
    @PatchMapping("/bank-cards/{id}/status") public Map<String,Object> cardStatus(@PathVariable long id,@RequestBody Map<String,Object> input){account.status(id,Db.text(input,"status"));return account.card(account.userId(),id);}
    @PostMapping("/bank-cards/{id}/unbind") public Map<String,Object> unbind(@PathVariable long id,@RequestBody Map<String,Object> input){account.unbind(id,input);return Map.of("unbound",true);}
    @GetMapping("/fund-accounts") public List<Map<String,Object>> fundAccounts(){return db.rows("SELECT a.*,r.name registrar_name FROM fund_accounts a JOIN registrars r ON r.id=a.registrar_id WHERE a.user_id=? ORDER BY a.id",account.userId());}
    @GetMapping("/trading-accounts/{id}/wallet") public Map<String,Object> wallet(@PathVariable long id){account.lockAccount(account.userId(),id);return db.one("SELECT * FROM wallets WHERE trading_account_id=?",id);}
    @PostMapping("/trading-accounts/{id}/top-ups") public Map<String,Object> topup(@PathVariable long id,@RequestBody Map<String,Object> input){return account.cashOperation(id,input,true);}
    @PostMapping("/trading-accounts/{id}/withdrawals") public Map<String,Object> withdrawal(@PathVariable long id,@RequestBody Map<String,Object> input){return account.cashOperation(id,input,false);}
    @GetMapping("/cash-entries") public List<Map<String,Object>> cashEntries(@RequestParam(required=false) Long tradingAccountId){long uid=account.userId();if(tradingAccountId!=null)account.lockAccount(uid,tradingAccountId);return db.rows("SELECT e.*,t.account_no,c.bank_name,c.sim_number FROM cash_entries e JOIN trading_accounts t ON t.id=e.trading_account_id JOIN bank_cards c ON c.id=t.bank_card_id WHERE e.user_id=?"+(tradingAccountId==null?"":" AND e.trading_account_id=?")+" ORDER BY e.id DESC LIMIT 1000",tradingAccountId==null?new Object[]{uid}:new Object[]{uid,tradingAccountId});}
    @PostMapping("/trades/quotes") public Map<String,Object> quote(@RequestBody Map<String,Object> input){return trades.quote(account.userId(),input);}
    @PostMapping("/orders") public Map<String,Object> submit(@RequestBody Map<String,Object> input){return trades.submit(account.userId(),input);}
    @GetMapping("/orders") public List<Map<String,Object>> orders(@RequestParam(required=false) Long tradingAccountId){long uid=account.userId();if(tradingAccountId!=null)account.lockAccount(uid,tradingAccountId);return db.rows(trades.orderQuery()+" WHERE o.user_id=?"+(tradingAccountId==null?"":" AND o.trading_account_id=?")+" ORDER BY o.id DESC LIMIT 1000",tradingAccountId==null?new Object[]{uid}:new Object[]{uid,tradingAccountId});}
    @GetMapping("/orders/{id}") public Map<String,Object> order(@PathVariable long id){return trades.order(account.userId(),id);}
    @PostMapping("/orders/{id}/cancel") public Map<String,Object> cancel(@PathVariable long id){return trades.cancel(account.userId(),id);}
    @PostMapping("/redemption-groups") public Map<String,Object> group(@RequestBody Map<String,Object> input){return trades.group(account.userId(),input);}
    @GetMapping("/redemption-groups/{id}") public Map<String,Object> groupView(@PathVariable long id){return trades.groupView(account.userId(),id);}
    @PostMapping("/redemption-groups/{id}/cancel") public Map<String,Object> cancelGroup(@PathVariable long id){return trades.cancelGroup(account.userId(),id);}
    @GetMapping("/portfolio") public Map<String,Object> portfolio(){return portfolio.portfolio(account.userId());}
    @GetMapping("/card-change-requests") public List<Map<String,Object>> changes(){return db.rows(changes.query()+" WHERE r.user_id=? ORDER BY r.id DESC",account.userId());}
    @PostMapping("/card-change-requests") public Map<String,Object> change(@RequestBody Map<String,Object> input){return changes.request(input);}
    @GetMapping("/notifications") public List<Map<String,Object>> notifications(){return db.rows("SELECT id,type,source_id,title,message,is_read `read`,created_at FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 500",account.userId());}
    @PatchMapping("/notifications/{id}") public Map<String,Object> read(@PathVariable long id,@RequestBody Map<String,Object> input){long uid=account.userId();db.one("SELECT id FROM notifications WHERE id=? AND user_id=?",id,uid);db.update("UPDATE notifications SET is_read=? WHERE id=? AND user_id=?",Db.yes(input.get("read")),id,uid);return Map.of("updated",true);}
    @GetMapping("/announcements") public List<Map<String,Object>> announcements(){account.userId();return db.rows("SELECT * FROM announcements ORDER BY id DESC LIMIT 100");}
    @GetMapping("/exports/{type}") public ResponseEntity<String> export(@PathVariable String type){long uid=account.userId();if(!Set.of("orders","cash-entries").contains(type))throw ApiException.missing();StringBuilder result=new StringBuilder("\uFEFF");long cursor=0;boolean header=false;while(true){var rows=db.rows("SELECT * FROM "+("orders".equals(type)?"orders":"cash_entries")+" WHERE user_id=? AND id>? ORDER BY id LIMIT 200",uid,cursor);if(rows.isEmpty())break;if(!header){result.append(String.join(",",rows.getFirst().keySet())).append("\r\n");header=true;}for(var row:rows){result.append(row.values().stream().map(CoreController::csvCell).reduce((a,b)->a+","+b).orElse("")).append("\r\n");cursor=Db.id(row.get("id"));}}
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+type+".csv\"").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(result.toString());}
    public static String csvCell(Object value){String s=value==null?"":value.toString();if(s.matches("^[=+@\\t\\r].*")||(s.startsWith("-")&&!s.matches("-?[0-9]+(\\.[0-9]+)?")))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    @GetMapping("/admin/dashboard") public Map<String,Object> dashboard(){account.requireRole("OPERATOR");return Map.of("users",db.scalar("SELECT COUNT(*) FROM users").longValue(),"accounts",db.scalar("SELECT COUNT(*) FROM trading_accounts").longValue(),"funds",db.scalar("SELECT COUNT(*) FROM fund_classes").longValue(),"orders",db.scalar("SELECT COUNT(*) FROM orders").longValue(),"pendingOrders",db.scalar("SELECT COUNT(*) FROM orders WHERE status='SUBMITTED'").longValue(),"totalAssets",Db.money(db.rows("SELECT id FROM users").stream().map(u->Db.decimal(portfolio.portfolio(Db.id(u.get("id"))).get("totalAssets"))).reduce(BigDecimal.ZERO,BigDecimal::add)).toPlainString(),"jobs",db.scalar("SELECT COUNT(*) FROM job_runs").longValue(),"pendingPayments",db.scalar("SELECT COUNT(*) FROM payouts WHERE status!='PAID'").longValue());}
    @GetMapping("/admin/users") public List<Map<String,Object>> users(){account.requireRole("OPERATOR");return db.rows("SELECT id,username,display_name,role,enabled,opened,risk_level,risk_expires_at,must_change_password,created_at FROM users ORDER BY id DESC LIMIT 1000");}
    @PatchMapping("/admin/users/{id}") @Transactional public Map<String,Object> userStatus(@PathVariable long id,@RequestBody Map<String,Object> input){account.requireRole("ADMIN");if(id==account.userId()&&!Db.yes(input.get("enabled")))throw ApiException.bad("不能停用当前管理员");db.one("SELECT id FROM users WHERE id=?",id);db.update("UPDATE users SET enabled=? WHERE id=?",Db.yes(input.get("enabled")),id);db.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=(SELECT username FROM users WHERE id=?)",id);account.audit("USER_STATUS","USER",id,"enabled="+input.get("enabled"));return Map.of("updated",true);}
    @PostMapping("/admin/users") @Transactional public Map<String,Object> createOperator(@RequestBody Map<String,Object> input){account.requireRole("ADMIN");if(!"OPERATOR".equals(input.get("role")))throw ApiException.bad("仅可新增OPERATOR操作员");var u=account.register(input);db.update("UPDATE users SET role='OPERATOR',must_change_password=TRUE WHERE id=?",u.get("id"));account.audit("CREATE_OPERATOR","USER",Db.id(u.get("id")),"新增操作员");return db.one("SELECT id,username,display_name,role FROM users WHERE id=?",u.get("id"));}
    @PostMapping("/admin/users/{id}/reset-password") @Transactional public Map<String,Object> resetPassword(@PathVariable long id,@RequestBody Map<String,Object> input){account.requireRole("ADMIN");String password=Db.text(input,"newPassword");AccountService.validatePassword(password);db.one("SELECT id FROM users WHERE id=? FOR UPDATE",id);db.update("UPDATE users SET password_hash=?,must_change_password=TRUE WHERE id=?",passwords.encode(password),id);db.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=(SELECT username FROM users WHERE id=?)",id);account.audit("RESET_PASSWORD","USER",id,"管理员重置临时密码，已注销会话并要求修改");return Map.of("reset",true);}
    @GetMapping("/admin/accounts") public List<Map<String,Object>> accounts(){account.requireRole("OPERATOR");return db.rows(account.cardQuery()+" ORDER BY t.id DESC LIMIT 1000");}
    @GetMapping("/admin/orders") public List<Map<String,Object>> adminOrders(){account.requireRole("OPERATOR");return db.rows(trades.orderQuery()+" ORDER BY o.id DESC LIMIT 1000");}
    @GetMapping("/admin/positions") public List<Map<String,Object>> positions(){account.requireRole("OPERATOR");return portfolio.positions(null);}
    @GetMapping("/admin/audit") public List<Map<String,Object>> audit(){account.requireRole("ADMIN");return db.rows("SELECT a.*,u.username actor_name FROM audit_events a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.id DESC LIMIT 1000");}
    @GetMapping("/admin/funds") public List<Map<String,Object>> adminFunds(){account.requireRole("OPERATOR");return funds.funds();}
    @PostMapping("/admin/funds") public Map<String,Object> addFund(@RequestBody Map<String,Object> input){return funds.saveFund(null,input);}
    @PatchMapping("/admin/funds/{id}") public Map<String,Object> updateFund(@PathVariable long id,@RequestBody Map<String,Object> input){return funds.saveFund(id,input);}
    @GetMapping("/admin/navs") public List<Map<String,Object>> navs(){account.requireRole("OPERATOR");return db.rows("SELECT n.*,f.name fund_name FROM nav_records n JOIN fund_classes f ON f.id=n.fund_class_id ORDER BY n.date DESC,n.id DESC LIMIT 1000");}
    @PostMapping("/admin/navs") public Map<String,Object> addNav(@RequestBody Map<String,Object> input){return funds.saveNav(input);}
    @GetMapping("/admin/fee-tiers") public List<Map<String,Object>> feeTiers(@RequestParam(required=false) Long fundClassId){account.requireRole("OPERATOR");return db.rows("SELECT t.*,f.name fund_name FROM fee_tiers t JOIN fund_classes f ON f.id=t.fund_class_id"+(fundClassId==null?"":" WHERE fund_class_id=?")+" ORDER BY fund_class_id,min_amount",fundClassId==null?new Object[0]:new Object[]{fundClassId});}
    @PutMapping("/admin/fee-tiers/{id}") @Transactional public Map<String,Object> feeTiers(@PathVariable long id,@RequestBody Map<String,Object> input){
        account.requireRole("OPERATOR");var fund=db.one("SELECT id,min_purchase FROM fund_classes WHERE id=? FOR UPDATE",id);
        Object raw=input.get("tiers");if(!(raw instanceof List<?> tiers))throw ApiException.bad("费率档位须为数组");
        db.update("DELETE FROM fee_tiers WHERE fund_class_id=?",id);Set<BigDecimal> thresholds=new HashSet<>();
        for(Object t:tiers){
            if(!(t instanceof Map<?,?> tier))throw ApiException.bad("费率档位格式错误");
            BigDecimal min=Db.decimal(tier.get("minAmount")),rate=Db.decimal(tier.get("rate"));Object fixed=tier.get("fixedFee");
            BigDecimal fee=fixed==null||fixed.toString().isBlank()?null:Db.decimal(fixed);
            if(min.signum()<0||min.scale()>2||rate.signum()<0||rate.compareTo(BigDecimal.ONE)>0||rate.scale()>8||(fee!=null&&(fee.signum()<0||fee.scale()>2))||!thresholds.add(min.stripTrailingZeros()))throw ApiException.bad("档位金额、费率或固定费用无效或重复");
            if(fee!=null&&fee.compareTo(min.max(Db.decimal(fund.get("minPurchase"))))>=0)throw ApiException.bad("固定费用须小于档位最小适用申购本金");
            db.update("INSERT INTO fee_tiers(fund_class_id,min_amount,rate,fixed_fee) VALUES(?,?,?,?)",id,min,rate,fee);
        }
        db.update("UPDATE fund_classes SET rule_version=rule_version+1 WHERE id=?",id);account.audit("FEE_TIERS","FUND",id,"更新费率档位，历史订单规则保持");return funds.fund(id);
    }
    @PostMapping("/admin/navs/import") @Transactional public Map<String,Object> importNav(@RequestParam("file") MultipartFile file) throws Exception{account.requireRole("OPERATOR");if(file.getSize()>5*1024*1024)throw ApiException.bad("CSV最大5MB");String csv=new String(file.getBytes(),StandardCharsets.UTF_8).replace("\uFEFF","");String[] lines=csv.split("\\R");if(lines.length<2||!lines[0].trim().equals("fundClassId,date,nav"))throw ApiException.bad("CSV表头须为fundClassId,date,nav");int count=0;for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;String[] values=lines[i].split(",",-1);try{if(values.length!=3)throw ApiException.bad("列数错误");funds.saveNav(Map.of("fundClassId",Long.parseLong(values[0].trim()),"date",values[1].trim(),"nav",values[2].trim()));count++;}catch(RuntimeException ex){throw ApiException.bad("CSV第"+(i+1)+"行："+ex.getMessage());}}return Map.of("imported",count);}
    @GetMapping("/admin/trading-calendar") public List<Map<String,Object>> calendar(){account.requireRole("OPERATOR");return db.rows("SELECT * FROM trading_calendar WHERE date>=? ORDER BY date LIMIT 500",account.clock().toLocalDate().minusDays(30));}
    @PostMapping("/admin/trading-calendar") @Transactional public Map<String,Object> saveCalendar(@RequestBody Map<String,Object> input){account.requireRole("OPERATOR");LocalDate date=LocalDate.parse(Db.text(input,"date"));if(db.scalar("SELECT COUNT(*) FROM orders WHERE application_date=?",date).signum()>0)throw ApiException.conflict("已有申请归属该日，不能修改日历");db.update("INSERT INTO trading_calendar(date,open) VALUES(?,?) ON DUPLICATE KEY UPDATE open=VALUES(open)",date,Db.yes(input.get("open")));account.audit("CALENDAR","CALENDAR",0,date.toString());return input;}
    @GetMapping("/admin/jobs") public List<Map<String,Object>> jobs(){account.requireRole("OPERATOR");return db.rows("SELECT * FROM job_runs ORDER BY id DESC LIMIT 500");}
    @GetMapping("/admin/jobs/context") public Map<String,Object> jobContext(){account.requireRole("OPERATOR");return Map.of("businessTime",account.clock().toString(),"actualTime",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString(),"demo",account.isDemo());}
    @PostMapping("/admin/jobs") public Map<String,Object> jobs(@RequestBody Map<String,Object> input){account.requireRole("OPERATOR");return runJobs(Db.text(input,"type"),LocalDate.parse(Db.text(input,"date")));}
    public Map<String,Object> runJobs(String type,LocalDate date){if(!Set.of("CONFIRM","PAY","SIP","MONEY","MARKET","ALL").contains(type))throw ApiException.bad("任务类型无效");
        if(date.isAfter(account.clock().toLocalDate()))throw ApiException.conflict("不能处理未来日期的任务");
        LocalDateTime actualRunAt=LocalDateTime.now(ZoneId.of("Asia/Shanghai")),businessTime=account.clock();
        int processed=0,waiting=0,failed=0;List<Object> details=new ArrayList<>();
        List<Map<String,Object>> results=new ArrayList<>();
        try {
            if(type.equals("ALL")){results.add(trades.run("MARKET",date));results.add(trades.run("CONFIRM",date));}else if(!type.equals("PAY"))results.add(trades.run(type,date));
            var service=advanced.getIfAvailable();if(service!=null)results.add(service.run(type,date));
            if(Set.of("PAY","ALL").contains(type)){results.add(trades.run("PAY",date));if(service!=null){service.syncPayments();results.forEach(service::refreshDividendWaiting);}}
        } catch(RuntimeException ex) { failed++;details.add("批次执行异常："+Objects.requireNonNullElse(ex.getMessage(),ex.getClass().getSimpleName())); }
        for(var result:results){processed+=((Number)result.get("processed")).intValue();waiting+=((Number)result.get("waiting")).intValue();failed+=((Number)result.get("failed")).intValue();if(result.get("details") instanceof List<?> d)details.addAll(d);}
        String status=failed>0?(processed>0?"PARTIAL":"FAILED"):waiting>0?"WAITING":processed>0?"COMPLETED":"NO_WORK";
        if(status.equals("NO_WORK"))details.add("本次无到期或新增的可处理业务；已完成的数据不会重复处理。");
        long jobId=db.insert("INSERT INTO job_runs(type,date,status,processed,waiting,failed,details,created_at,actual_run_at) VALUES(?,?,?,?,?,?,?,?,?)",type,date,status,processed,waiting,failed,String.join("\n",details.stream().map(Object::toString).toList()),businessTime,actualRunAt);
        return Map.of("processed",processed,"waiting",waiting,"failed",failed,"details",details,"status",status,"jobId",jobId,"businessTime",businessTime.toString(),"actualRunAt",actualRunAt.toString());
    }
    @GetMapping("/admin/payouts") public List<Map<String,Object>> payouts(){account.requireRole("OPERATOR");return db.rows("SELECT p.*,o.sim_number source_card_number,t.sim_number target_card_number,a.account_no FROM payouts p JOIN bank_cards o ON o.id=p.source_card_id JOIN bank_cards t ON t.id=p.target_card_id JOIN trading_accounts a ON a.id=p.trading_account_id ORDER BY p.id DESC LIMIT 1000");}
    @GetMapping("/admin/card-change-requests") public List<Map<String,Object>> adminChanges(){account.requireRole("ADMIN");return db.rows(changes.query()+" ORDER BY r.id DESC LIMIT 500");}
    @PostMapping("/admin/card-change-requests/{id}/approve") public Map<String,Object> approve(@PathVariable long id,@RequestBody Map<String,Object> input){return changes.approve(id,input);}
    @PatchMapping("/admin/bank-cards/{id}/receive-state") public Map<String,Object> receive(@PathVariable long id,@RequestBody Map<String,Object> input){changes.receive(id,input);return Map.of("updated",true);}
    @GetMapping("/admin/reconciliation") public Map<String,Object> reconciliation(){account.requireRole("OPERATOR");return portfolio.reconciliation();}
    @GetMapping("/admin/announcements") public List<Map<String,Object>> adminAnnouncements(){account.requireRole("OPERATOR");return db.rows("SELECT * FROM announcements ORDER BY id DESC LIMIT 100");}
    @PostMapping("/admin/announcements") @Transactional public Map<String,Object> announcement(@RequestBody Map<String,Object> input){account.requireRole("OPERATOR");String title=Db.text(input,"title"),body=Db.text(input,"body");if(title.length()>200||body.length()>10000)throw ApiException.bad("公告内容过长");long id=db.insert("INSERT INTO announcements(title,body,created_at) VALUES(?,?,?)",title,body,account.clock());account.audit("ANNOUNCEMENT","ANNOUNCEMENT",id,title);return db.one("SELECT * FROM announcements WHERE id=?",id);}
    @GetMapping("/admin/clock") public Map<String,Object> clock(){account.requireRole("ADMIN");return Map.of("dateTime",account.clock().toString(),"demo",account.isDemo());}
    @PostMapping("/admin/clock") @Transactional public Map<String,Object> advance(@RequestBody Map<String,Object> input){account.requireRole("ADMIN");if(!account.isDemo())throw new ApiException(403,"DEMO_DISABLED","公开配置不允许修改时钟");LocalDateTime dateTime=LocalDateTime.parse(Db.text(input,"dateTime"));db.update("INSERT INTO app_settings(name,value) VALUES('demo_clock',?) ON DUPLICATE KEY UPDATE value=VALUES(value)",dateTime.toString());account.audit("DEMO_CLOCK","CLOCK",0,dateTime.toString());return clock();}
}
