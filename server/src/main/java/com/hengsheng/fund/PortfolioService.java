package com.hengsheng.fund;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PortfolioService {
    private final Db db;private final AccountService account;
    public PortfolioService(Db db,AccountService account){this.db=db;this.account=account;}
    public List<Map<String,Object>> positions(Long uid){return db.rows("SELECT l.user_id,l.trading_account_id,l.fund_class_id,f.name fund_name,c.bank_name,SUM(l.shares) shares,SUM(l.frozen_shares) frozen_shares,SUM(l.shares-l.frozen_shares) available_shares,SUM(l.cost) cost,COALESCE((SELECT nav FROM nav_records n WHERE n.fund_class_id=f.id AND n.published=TRUE AND n.date<=? ORDER BY date DESC LIMIT 1),1) nav FROM position_lots l JOIN fund_classes f ON f.id=l.fund_class_id JOIN trading_accounts t ON t.id=l.trading_account_id JOIN bank_cards c ON c.id=t.bank_card_id WHERE l.shares>0"+(uid==null?"":" AND l.user_id=?")+" GROUP BY l.user_id,l.trading_account_id,l.fund_class_id,f.name,c.bank_name,f.id ORDER BY l.trading_account_id,l.fund_class_id",uid==null?new Object[]{account.clock().toLocalDate()}:new Object[]{account.clock().toLocalDate(),uid}).stream().map(p->{var value=Db.money(Db.decimal(p.get("shares")).multiply(Db.decimal(p.get("nav"))));p.put("marketValue",value.toPlainString());p.put("profit",Db.money(value.subtract(Db.decimal(p.get("cost")))).toPlainString());return p;}).toList();}
    public Map<String,Object> portfolio(long uid){
        var positions=positions(uid);var accounts=db.rows("SELECT t.id,t.id trading_account_id,t.account_no,c.bank_name,c.sim_number,t.status,w.available,w.frozen FROM trading_accounts t JOIN bank_cards c ON c.id=t.bank_card_id JOIN wallets w ON w.trading_account_id=t.id WHERE t.user_id=? ORDER BY t.id",uid);
        BigDecimal available=BigDecimal.ZERO,frozen=BigDecimal.ZERO,pending=BigDecimal.ZERO,invested=BigDecimal.ZERO,assets=BigDecimal.ZERO,income=BigDecimal.ZERO;
        for(var a:accounts){long tid=Db.id(a.get("id"));BigDecimal av=Db.decimal(a.get("available")),fr=Db.decimal(a.get("frozen"));BigDecimal inv=positions.stream().filter(p->Db.id(p.get("tradingAccountId"))==tid).map(p->Db.decimal(p.get("marketValue"))).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal pend=db.scalar("SELECT COALESCE(SUM(amount),0) FROM payouts WHERE trading_account_id=? AND status!='PAID'",tid);
            if(table("offering_orders"))pend=pend.add(db.scalar("SELECT COALESCE(SUM(o.amount),0) FROM offering_orders s JOIN orders o ON o.id=s.order_id WHERE s.trading_account_id=? AND s.status='SUBMITTED'",tid));
            if(table("dividend_entitlements"))pend=pend.add(db.scalar("SELECT COALESCE(SUM(e.amount),0) FROM dividend_entitlements e JOIN dividend_events d ON d.id=e.event_id WHERE e.trading_account_id=? AND e.status='PENDING' AND e.payout_id IS NULL AND d.ex_date<=?",tid,account.clock().toLocalDate()));
            BigDecimal total=av.add(fr).add(inv).add(pend),external=db.scalar("SELECT COALESCE(SUM(amount),0) FROM cash_entries WHERE trading_account_id=? AND source_type IN('INITIAL','TOPUP','WITHDRAW')",tid),profit=total.subtract(external);
            a.put("invested",Db.money(inv).toPlainString());a.put("pending",Db.money(pend).toPlainString());a.put("totalAssets",Db.money(total).toPlainString());a.put("totalIncome",Db.money(profit).toPlainString());
            available=available.add(av);frozen=frozen.add(fr);pending=pending.add(pend);invested=invested.add(inv);assets=assets.add(total);income=income.add(profit);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("available",Db.money(available).toPlainString());result.put("frozen",Db.money(frozen).toPlainString());result.put("pending",Db.money(pending).toPlainString());result.put("invested",Db.money(invested).toPlainString());result.put("totalAssets",Db.money(assets).toPlainString());result.put("totalIncome",Db.money(income).toPlainString());result.put("positions",positions);result.put("accounts",accounts);return result;
    }
    private boolean table(String name){return db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",name).signum()>0;}
    public Map<String,Object> reconciliation(){List<String> issues=new ArrayList<>();
        for(var w:db.rows("SELECT * FROM wallets")){long tid=Db.id(w.get("tradingAccountId"));BigDecimal av=db.scalar("SELECT COALESCE(SUM(available_delta),0) FROM cash_entries WHERE trading_account_id=?",tid),fr=db.scalar("SELECT COALESCE(SUM(frozen_delta),0) FROM cash_entries WHERE trading_account_id=?",tid);if(av.compareTo(Db.decimal(w.get("available")))!=0||fr.compareTo(Db.decimal(w.get("frozen")))!=0)issues.add("资金账户"+tid+"与流水不符");}
        for(var lot:db.rows("SELECT * FROM position_lots")){long lid=Db.id(lot.get("id"));BigDecimal shares=db.scalar("SELECT COALESCE(SUM(shares_delta),0) FROM share_entries WHERE lot_id=?",lid);if(shares.compareTo(Db.decimal(lot.get("shares")))!=0)issues.add("持仓批次"+lid+"与份额流水不符");}
        return Map.of("ok",issues.isEmpty(),"issues",issues);
    }
}
