package com.hengsheng.fund;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.hengsheng.fund.AdvancedSupport.*;

@RestController
@RequestMapping("/api")
class AdvancedController {
    private final AdvancedSupport s;
    private final ClearingService clearing;
    private final OfferingService offerings;
    private final SipService sip;
    private final DividendService dividends;
    private final ConversionService conversions;
    private final MoneyIncomeService money;
    private final LifecycleService lifecycle;
    private final CashCorrectionService corrections;
    AdvancedController(AdvancedSupport s,OfferingService offerings,SipService sip,DividendService dividends,
            ConversionService conversions,MoneyIncomeService money,LifecycleService lifecycle,CashCorrectionService corrections,ClearingService clearing) {
        this.clearing=clearing;
        this.s=s;this.offerings=offerings;this.sip=sip;this.dividends=dividends;
        this.conversions=conversions;this.money=money;this.lifecycle=lifecycle;
        this.corrections=corrections;
    }
    private ResponseEntity<Map<String,Object>> enqueue(String type,long targetId,Map<String,Object> input){var parameters=new LinkedHashMap<>(input);parameters.put("targetId",targetId);var day=type.equals("LIQUIDATION")?date(input.get("date")):s.now().toLocalDate();long runId=clearing.submit(type,day,parameters,s.accounts.userId(),input.getOrDefault("requestKey",UUID.randomUUID().toString()).toString());return clearing.submissionResponse(runId,Map.of());}
    private boolean staff() { return !"INVESTOR".equals(s.accounts.me().get("role")); }
    private void operator() { s.accounts.requireRole("OPERATOR"); }
    @GetMapping("/offering-orders")
    List<Map<String,Object>> offerings() {
        long uid=s.accounts.userId();
        return s.db.rows("SELECT o.*,f.name fund_name,c.bank_name,c.sim_number,p.interest,tc.confirmation_no FROM orders o JOIN fund_classes f ON f.id=o.fund_class_id JOIN bank_cards c ON c.id=o.source_card_id JOIN offering_orders p ON p.order_id=o.id LEFT JOIN trade_confirmations tc ON tc.id=o.current_confirmation_id WHERE o.type='OFFERING' AND o.user_id=? ORDER BY o.id DESC",uid);
    }
    @PostMapping("/offering-orders")
    Map<String,Object> subscribe(@RequestBody Map<String,Object> input) { return offerings.subscribe(s.accounts.userId(),input); }
    @PostMapping("/admin/offerings/{id}/finalize")
    ResponseEntity<Map<String,Object>> finalizeOffering(@PathVariable long id,@RequestBody Map<String,Object> input) { operator();return enqueue("OFFERING_FINALIZE",id,input); }

    @GetMapping({"/sip-plans","/admin/sip-plans"})
    List<Map<String,Object>> plans() {
        long uid=s.accounts.userId();boolean all=staff();
        return s.db.rows("SELECT p.*,f.name fund_name,c.bank_name FROM sip_plans p JOIN fund_classes f ON f.id=p.fund_class_id JOIN bank_cards c ON c.id=p.bank_card_id"+(all?"":" WHERE p.user_id=?")+" ORDER BY p.id DESC",all?new Object[]{}:new Object[]{uid});
    }
    @PostMapping("/sip-plans")
    Map<String,Object> createPlan(@RequestBody Map<String,Object> input) { return sip.create(s.accounts.userId(),input); }
    @PatchMapping("/sip-plans/{id}")
    Map<String,Object> changePlan(@PathVariable long id,@RequestBody Map<String,Object> input) { return sip.change(s.accounts.userId(),id,input); }
    @GetMapping("/sip-runs")
    List<Map<String,Object>> runs() {
        long uid=s.accounts.userId();boolean all=staff();
        return s.db.rows("SELECT r.*,p.trading_account_id,f.name fund_name FROM sip_runs r JOIN sip_plans p ON p.id=r.plan_id JOIN fund_classes f ON f.id=p.fund_class_id"+(all?"":" WHERE p.user_id=?")+" ORDER BY r.id DESC LIMIT 1000",all?new Object[]{}:new Object[]{uid});
    }
    @GetMapping("/dividend-preferences")
    List<Map<String,Object>> preferences() { return s.db.rows("SELECT * FROM dividend_preferences WHERE user_id=?",s.accounts.userId()); }
    @PutMapping("/dividend-preferences/{id}")
    Map<String,Object> preference(@PathVariable long id,@RequestBody Map<String,Object> input) { return dividends.preference(s.accounts.userId(),id,text(input,"mode")); }
    @GetMapping("/dividends")
    List<Map<String,Object>> dividends() {
        return s.db.rows("SELECT d.*,d.shares eligible_shares,(SELECT COALESCE(SUM(l.shares_delta),0) FROM share_entries l WHERE l.source_type='DIVIDEND' AND l.source_id=CAST(d.id AS CHAR) AND l.trading_account_id=d.trading_account_id AND l.event_type='ADD' AND l.effect_state='ACTIVE' AND l.reversal_of IS NULL) reinvest_shares,e.fund_class_id,e.record_date,e.ex_date,e.payment_date,e.per_share,f.name fund_name,c.bank_name,c.sim_number FROM dividend_entitlements d JOIN dividend_events e ON e.id=d.event_id JOIN fund_classes f ON f.id=e.fund_class_id JOIN bank_cards c ON c.id=d.source_card_id WHERE d.user_id=? ORDER BY d.id DESC",s.accounts.userId());
    }
    @GetMapping("/admin/dividends")
    List<Map<String,Object>> adminDividends() { operator();return s.db.rows("SELECT e.*,f.name fund_name FROM dividend_events e JOIN fund_classes f ON f.id=e.fund_class_id ORDER BY e.id DESC"); }
    @PostMapping("/admin/dividends")
    Map<String,Object> createDividend(@RequestBody Map<String,Object> input) { operator();return dividends.create(input); }
    @PostMapping("/admin/dividends/{id}/snapshot")
    ResponseEntity<Map<String,Object>> snapshot(@PathVariable long id,@RequestBody(required=false) Map<String,Object> input) { operator();return enqueue("DIVIDEND_SNAPSHOT",id,input==null?Map.of():input); }
    @PostMapping("/admin/dividends/{id}/execute")
    ResponseEntity<Map<String,Object>> execute(@PathVariable long id,@RequestBody(required=false) Map<String,Object> input) { operator();return enqueue("DIVIDEND_EXECUTE",id,input==null?Map.of():input); }

    @GetMapping("/money-income")
    List<Map<String,Object>> income() {
        return s.db.rows("SELECT m.*,m.shares eligible_shares,m.income amount,m.applied_shares added_shares,f.name fund_name,c.bank_name,r.income_per_ten_thousand,r.annual_yield FROM money_income_snapshots m JOIN fund_classes f ON f.id=m.fund_class_id JOIN bank_cards c ON c.id=m.source_card_id LEFT JOIN money_income_records r ON r.fund_class_id=m.fund_class_id AND r.date=m.date WHERE m.user_id=? ORDER BY m.date DESC,m.id DESC",s.accounts.userId());
    }
    @GetMapping("/admin/money-income")
    List<Map<String,Object>> adminIncome() { operator();return s.db.rows("SELECT r.*,f.name fund_name FROM money_income_records r JOIN fund_classes f ON f.id=r.fund_class_id ORDER BY r.date DESC LIMIT 1000"); }
    @PostMapping("/admin/money-income")
    Map<String,Object> saveIncome(@RequestBody Map<String,Object> input) { operator();return money.write(input); }

    @GetMapping({"/conversion-pairs","/admin/conversion-pairs"})
    List<Map<String,Object>> pairs() {
        s.accounts.userId();
        return s.db.rows("SELECT p.*,a.name source_name,b.name target_name FROM conversion_pairs p JOIN fund_classes a ON a.id=p.source_class_id JOIN fund_classes b ON b.id=p.target_class_id ORDER BY p.source_class_id,p.target_class_id");
    }
    @PostMapping("/admin/conversion-pairs")
    @Transactional
    Map<String,Object> pair(@RequestBody Map<String,Object> input) {
        operator();long source=id(input,"sourceClassId"),target=id(input,"targetClassId");
        var a=s.db.one("SELECT * FROM fund_classes WHERE id=?",source);var b=s.db.one("SELECT * FROM fund_classes WHERE id=?",target);
        if(source==target || !a.get("manager").equals(b.get("manager")) || "MONEY".equals(a.get("type")) || "MONEY".equals(b.get("type"))) throw new IllegalArgumentException("请选择同管理人的不同净值产品");
        s.db.update("INSERT INTO conversion_pairs(source_class_id,target_class_id,enabled) VALUES(?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)",source,target,input.getOrDefault("enabled",true));
        s.changed("CONVERSION_PAIR","FUND",source,"目标"+target);return input;
    }
    @GetMapping("/conversions")
    List<Map<String,Object>> conversions() {
        return s.db.rows("SELECT c.*,tc.confirmation_no,a.name source_name,b.name target_name FROM conversions c JOIN fund_classes a ON a.id=c.source_class_id JOIN fund_classes b ON b.id=c.target_class_id LEFT JOIN trade_confirmations tc ON tc.id=c.current_confirmation_id WHERE c.user_id=? ORDER BY c.id DESC",s.accounts.userId());
    }
    @PostMapping("/conversions")
    Map<String,Object> convert(@RequestBody Map<String,Object> input) { return conversions.submit(s.accounts.userId(),input); }
    @PostMapping("/conversions/quotes")
    Map<String,Object> conversionQuote(@RequestBody Map<String,Object> input) { return conversions.quote(s.accounts.userId(),input); }
    @PostMapping("/conversions/{id}/cancel")
    Map<String,Object> cancelConversion(@PathVariable long id) { return conversions.cancel(s.accounts.userId(),id); }
    @PostMapping("/admin/funds/{id}/terminate")
    Map<String,Object> terminate(@PathVariable long id,@RequestBody Map<String,Object> input) { s.accounts.requireRole("ADMIN");return lifecycle.terminate(id,input); }
    @PostMapping("/admin/liquidations")
    ResponseEntity<Map<String,Object>> liquidate(@RequestBody Map<String,Object> input) { s.accounts.requireRole("ADMIN");return enqueue("LIQUIDATION",id(input,"fundClassId"),input); }
    @GetMapping("/admin/liquidations")
    List<Map<String,Object>> liquidations() { operator();return s.db.rows("SELECT e.*,f.name fund_name FROM liquidation_events e JOIN fund_classes f ON f.id=e.fund_class_id ORDER BY e.id DESC"); }
    @GetMapping("/admin/cash-entries")
    List<Map<String,Object>> cashEntries() { operator();return s.db.rows("SELECT e.*,u.display_name,t.account_no,c.bank_name FROM cash_entries e JOIN users u ON u.id=e.user_id JOIN trading_accounts t ON t.id=e.trading_account_id JOIN bank_cards c ON c.id=t.bank_card_id ORDER BY e.id DESC LIMIT 1000"); }
    @GetMapping("/admin/corrections")
    List<Map<String,Object>> corrections() { s.accounts.requireRole("ADMIN");return s.db.rows("SELECT r.*,u.display_name,t.account_no FROM cash_corrections r JOIN users u ON u.id=r.user_id JOIN trading_accounts t ON t.id=r.trading_account_id ORDER BY r.id DESC LIMIT 1000"); }
    @PostMapping("/admin/corrections")
    Map<String,Object> correct(@RequestBody Map<String,Object> input) { return corrections.correct(input); }
}
