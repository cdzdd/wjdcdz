package com.hengsheng.fund;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class AdvancedSupport {
    final Db db;
    final AccountService accounts;
    final TradeService trades;
    private final TransactionTemplate transactions;

    AdvancedSupport(Db db, AccountService accounts, TradeService trades, PlatformTransactionManager manager) {
        this.db = db; this.accounts = accounts; this.trades = trades;
        transactions = new TransactionTemplate(manager);
    }
    <T> T tx(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    LocalDateTime now() { return accounts.clock(); }
    static long id(Map<String,Object> values, String key) {
        Object value = values.get(key);
        if (value == null) throw new IllegalArgumentException("缺少" + key);
        return Long.parseLong(value.toString());
    }
    static String text(Map<String,Object> values, String key) {
        String value = String.valueOf(values.getOrDefault(key, "")).trim();
        if (value.isEmpty() || value.length() > 200) throw new IllegalArgumentException("请填写有效的" + key);
        return value;
    }
    static BigDecimal decimal(Object value) { return new BigDecimal(String.valueOf(value)); }
    static BigDecimal amount(Map<String,Object> values, String key, int scale) {
        BigDecimal value = decimal(values.get(key));
        if (value.signum() <= 0 || value.scale() > scale || value.precision() > 18)
            throw new IllegalArgumentException("金额/份额必须为正数且精度合法");
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    static boolean yes(Object value) { return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equals(String.valueOf(value)); }
    static LocalDate date(Object value) { return LocalDate.parse(value.toString().substring(0, 10)); }
    boolean due(LocalDate date, int hour) { return !now().isBefore(date.atTime(hour, 0)); }

    Map<String,Object> account(long userId, long accountId, boolean debit) {
        Map<String,Object> row = db.one("SELECT a.*,c.status AS card_status,c.receive_state,c.single_limit,c.daily_limit FROM trading_accounts a JOIN bank_cards c ON c.id=a.bank_card_id WHERE a.id=? AND a.user_id=? FOR UPDATE",accountId,userId);
        if (!"ACTIVE".equals(row.get("status"))) throw new IllegalArgumentException("交易账户已关闭");
        if (debit && (!"ACTIVE".equals(row.get("cardStatus")) || yes(row.get("debitFrozen"))))
            throw new IllegalArgumentException("银行卡已停止扣款或正在换卡");
        return row;
    }
    Map<String,Object> eligible(long userId, long accountId, long fundId, String expectedState) {
        return eligible(userId,accountId,fundId,expectedState,true);
    }
    Map<String,Object> eligible(long userId, long accountId, long fundId, String expectedState, boolean debit) {
        account(userId,accountId,debit);
        Map<String,Object> user = db.one("SELECT * FROM users WHERE id=?",userId);
        Map<String,Object> fund = db.one("SELECT * FROM fund_classes WHERE id=?",fundId);
        if (!yes(user.get("opened")) || !yes(user.get("enabled"))) throw new IllegalArgumentException("请先开通模拟交易账户");
        if (user.get("riskExpiresAt") == null || date(user.get("riskExpiresAt")).isBefore(now().toLocalDate())
                || id(user,"riskLevel") < id(fund,"riskLevel")) throw new IllegalArgumentException("风险测评过期或不匹配");
        if (!expectedState.equals(fund.get("state")) || !yes(fund.get("purchaseOpen"))) throw new IllegalArgumentException("基金当前不能购入");
        return fund;
    }
    void debit(long userId, long accountId, BigDecimal value, String type, String source) {
        Map<String,Object> account = account(userId,accountId,true);
        BigDecimal used = db.scalar("SELECT COALESCE(SUM(amount),0) FROM debit_quota_entries WHERE trading_account_id=? AND date=? AND released=FALSE",accountId,now().toLocalDate());
        if (value.compareTo(decimal(account.get("singleLimit")))>0 || used.add(value).compareTo(decimal(account.get("dailyLimit")))>0)
            throw new IllegalArgumentException("超过银行卡单笔或单日限额");
        Map<String,Object> wallet = db.one("SELECT * FROM wallets WHERE trading_account_id=? FOR UPDATE",accountId);
        if (decimal(wallet.get("available")).compareTo(value)<0) throw new IllegalArgumentException("所选银行卡资金不足");
        db.update("UPDATE wallets SET available=available-? WHERE trading_account_id=?",value,accountId);
        db.update("INSERT INTO cash_entries(user_id,trading_account_id,source_type,source_id,event_type,available_delta,frozen_delta,amount,created_at) VALUES(?,?,?,?,'DEBIT',?,0,?,?)",userId,accountId,type,source,value.negate(),value.negate(),now());
        db.update("INSERT INTO debit_quota_entries(trading_account_id,source_type,source_id,date,amount) VALUES(?,?,?,?,?)",accountId,type,source,now().toLocalDate(),value);
    }
    void validateCard(Map<String,Object> request, Map<String,Object> account) {
        if (id(request,"bankCardId") != id(account,"bankCardId") || id(request,"bindingVersion") != id(account,"bindingVersion"))
            throw new IllegalArgumentException("结算卡绑定已变化，请重新选择");
    }
    LocalDate openDate(LocalDate date) {
        for (int i=0;i<370;i++,date=date.plusDays(1)) {
            var rows = db.rows("SELECT * FROM trading_calendar WHERE date=?",date);
            if (rows.isEmpty()) throw new IllegalArgumentException("交易日历缺少" + date);
            if (yes(rows.getFirst().get("open"))) return date;
        }
        throw new IllegalArgumentException("没有可用开放日");
    }
    LocalDate nextOpen(LocalDate date, int count) {
        for(int i=0;i<count;i++) date=openDate(date.plusDays(1));
        return date;
    }
    void changed(String action, String object, long id, String reason) { accounts.audit(action,object,id,reason); }
}
