package com.hengsheng.fund;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hengsheng.fund.AdvancedSupport.*;

@Service
class CashCorrectionService {
    private final AdvancedSupport s;
    CashCorrectionService(AdvancedSupport s) { this.s=s; }
    @Transactional
    Map<String,Object> correct(Map<String,Object> input) {
        s.accounts.requireRole("ADMIN");long actor=s.accounts.userId();String key=text(input,"requestKey");
        if(key.length()>100) throw ApiException.bad("请求键过长");
        String fingerprint=TradeService.fingerprint(input);
        var old=s.db.rows("SELECT * FROM cash_corrections WHERE request_key=?",key);
        if(!old.isEmpty()) {
            if(!fingerprint.equals(old.getFirst().get("requestFingerprint"))) throw ApiException.conflict("更正请求键与原内容不一致");
            return old.getFirst();
        }
        var original=s.db.one("SELECT * FROM cash_entries WHERE id=? FOR UPDATE",id(input,"originalEntryId"));
        if(!Set.of("CREDIT","WITHDRAW").contains(original.get("eventType")))
            throw ApiException.bad("只能更正已完成入账或提现流水，冻结及份额更正须单独处理");
        BigDecimal delta=decimal(input.get("availableDelta"));
        if(delta.signum()==0 || delta.scale()>2 || delta.precision()-delta.scale()>18) throw ApiException.bad("更正差额须为非零且最多两位小数");
        if(delta.abs().compareTo(decimal(original.get("availableDelta")).abs())>0) throw ApiException.bad("单次更正差额不能超过原流水金额");
        long aid=id(original,"tradingAccountId"),uid=id(original,"userId");
        s.account(uid,aid,false);
        var wallet=s.db.one("SELECT * FROM wallets WHERE trading_account_id=? FOR UPDATE",aid);
        if(decimal(wallet.get("available")).add(delta).signum()<0) throw ApiException.conflict("更正后可用余额不足，不能动用冻结资金或其他账户");
        String reason=text(input,"reason");
        long id=s.db.insert("INSERT INTO cash_corrections(original_entry_id,actor_id,user_id,trading_account_id,available_delta,reason,request_key,request_fingerprint,created_at) VALUES(?,?,?,?,?,?,?,?,?)",original.get("id"),actor,uid,aid,money(delta),reason,key,fingerprint,s.now());
        s.db.update("UPDATE wallets SET available=available+? WHERE trading_account_id=?",delta,aid);
        s.accounts.cash(uid,aid,original.get("sourceType").toString(),"CORRECTION-"+id,"CORRECTION",delta,BigDecimal.ZERO);
        s.changed("CASH_CORRECTION","CASH_ENTRY",id(original,"id"),reason+"；更正记录"+id+"；差额"+delta.toPlainString());
        s.accounts.notifyUser(uid,"CASH_CORRECTION",Long.toString(id),"模拟资金流水已更正","原记录保留，追加差额"+delta.toPlainString()+"元。原因："+reason);
        return s.db.one("SELECT * FROM cash_corrections WHERE id=?",id);
    }
}
