package com.hengsheng.fund;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardChangeService {
    private final Db db;private final AccountService account;
    public CardChangeService(Db db,AccountService account){this.db=db;this.account=account;}
    public String query(){return "SELECT r.*,o.sim_number old_card_number,n.sim_number new_card_number,n.bank_name,n.holder_name,t.account_no FROM card_change_requests r JOIN bank_cards o ON o.id=r.old_card_id JOIN bank_cards n ON n.id=r.new_card_id JOIN trading_accounts t ON t.id=r.trading_account_id";}
    @Transactional public Map<String,Object> request(Map<String,Object> in){
        long uid=account.userId(),tid=Db.id(in.get("tradingAccountId"));String key=Db.text(in,"requestKey"),bank=Db.text(in,"bankName"),holder=Db.text(in,"holderName"),reason=Db.text(in,"reason");
        var prior=db.rows("SELECT * FROM card_change_requests WHERE user_id=? AND request_key=?",uid,key);if(!prior.isEmpty()){var r=prior.getFirst();var c=db.one("SELECT * FROM bank_cards WHERE id=?",r.get("newCardId"));if(Db.id(r.get("tradingAccountId"))!=tid||!bank.equals(c.get("bankName"))||!holder.equals(c.get("holderName"))||!reason.equals(r.get("reason")))throw ApiException.conflict("请求键已用于不同换卡内容");return db.one(query()+" WHERE r.id=?",r.get("id"));}
        var a=account.lockAccount(uid,tid);if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭");if(!bank.equals(a.get("bankName"))||!holder.equals(a.get("holderName")))throw ApiException.bad("受控换卡须为同银行、同模拟持卡人");
        if(db.scalar("SELECT COUNT(*) FROM card_change_requests WHERE trading_account_id=? AND status='PENDING'",tid).signum()>0)throw ApiException.conflict("该账户已有待审批换卡申请");
        if(reason.length()>500||key.length()>90)throw ApiException.bad("换卡信息过长");
        long cid=db.insert("INSERT INTO bank_cards(user_id,bank_name,holder_name,sim_number,request_key,status,single_limit,daily_limit) VALUES(?,?,?,?,?,'DISABLED',?,?)",uid,bank,holder,"SIM-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT),"CHANGE-"+key,a.get("singleLimit"),a.get("dailyLimit"));
        long rid=db.insert("INSERT INTO card_change_requests(user_id,trading_account_id,old_card_id,new_card_id,old_binding_version,reason,request_key,created_at) VALUES(?,?,?,?,?,?,?,?)",uid,tid,a.get("bankCardId"),cid,a.get("bindingVersion"),reason,key,account.clock());
        db.update("UPDATE trading_accounts SET debit_frozen=TRUE WHERE id=?",tid);account.audit("CARD_CHANGE_REQUEST","CARD_CHANGE",rid,reason);return db.one(query()+" WHERE r.id=?",rid);
    }
    @Transactional public Map<String,Object> approve(long rid,Map<String,Object> in){
        account.requireRole("ADMIN");String reason=Db.text(in,"reason");var r=db.one("SELECT * FROM card_change_requests WHERE id=? FOR UPDATE",rid);
        if("APPROVED".equals(r.get("status")))return db.one(query()+" WHERE r.id=?",rid);
        long uid=Db.id(r.get("userId")),tid=Db.id(r.get("tradingAccountId"));var a=account.lockAccount(uid,tid);
        if(!"ACTIVE".equals(a.get("status")))throw ApiException.conflict("交易账户已关闭，不能批准换卡");
        if(!"PENDING".equals(r.get("status"))||Db.id(a.get("bankCardId"))!=Db.id(r.get("oldCardId"))||Db.id(a.get("bindingVersion"))!=Db.id(r.get("oldBindingVersion")))throw ApiException.conflict("换卡申请或绑定版本已变化");
        if(db.scalar("SELECT COUNT(*) FROM orders WHERE trading_account_id=? AND type='PURCHASE' AND status='SUBMITTED'",tid).signum()>0)throw ApiException.conflict("请先处理或撤销该账户待确认扣款申请");
        long old=Db.id(r.get("oldCardId")),next=Db.id(r.get("newCardId")),version=Db.id(a.get("bindingVersion"))+1;
        db.update("UPDATE bank_cards SET status='REPLACED' WHERE id=?",old);db.update("UPDATE bank_cards SET status='ACTIVE' WHERE id=?",next);db.update("UPDATE trading_accounts SET bank_card_id=?,binding_version=?,debit_frozen=FALSE WHERE id=?",next,version,tid);
        db.update("INSERT INTO card_bindings(trading_account_id,bank_card_id,binding_version,change_request_id,created_at) VALUES(?,?,?,?,?)",tid,next,version,rid,account.clock());
        for(var p:db.rows("SELECT * FROM payouts WHERE trading_account_id=? AND status IN('PENDING','BLOCKED') FOR UPDATE",tid)){
            db.update("INSERT INTO payout_route_changes(payout_id,old_card_id,new_card_id,authorization_id,reason,created_at) VALUES(?,?,?,?,?,?)",p.get("id"),p.get("targetCardId"),next,rid,reason,account.clock());
            db.update("UPDATE payouts SET target_card_id=?,target_binding_version=?,authorization_id=?,status='PENDING',reason=NULL WHERE id=?",next,version,rid,p.get("id"));
        }
        db.update("UPDATE sip_plans SET bank_card_id=?,binding_version=? WHERE trading_account_id=? AND status!='TERMINATED'",next,version,tid);
        db.update("UPDATE card_change_requests SET status='APPROVED',new_binding_version=?,approval_reason=?,approved_by=?,approved_at=? WHERE id=?",version,reason,account.userId(),account.clock(),rid);
        account.audit("CARD_CHANGE_APPROVE","CARD_CHANGE",rid,reason);account.notifyUser(uid,"CARD_CHANGE",String.valueOf(rid),"受控换卡已完成","交易账户与历史来源保持，待支付款项已记录授权改付");return db.one(query()+" WHERE r.id=?",rid);
    }
    @Transactional public void receive(long cid,Map<String,Object> in){account.requireRole("ADMIN");String state=Db.text(in,"state"),reason=Db.text(in,"reason");if(!Set.of("RECEIVABLE","BLOCKED").contains(state))throw ApiException.bad("收款状态无效");db.one("SELECT id FROM bank_cards WHERE id=? FOR UPDATE",cid);db.update("UPDATE bank_cards SET receive_state=? WHERE id=?",state,cid);account.audit("RECEIVE_STATE","BANK_CARD",cid,state+"："+reason);}
}
