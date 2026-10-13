package com.hengsheng.fund;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NavRevisionService {
    private final Db db;
    public NavRevisionService(Db db){this.db=db;}
    @Transactional public long createRevisionSet(List<Map<String,Object>> rows,String requestKey,String reason,long actorId) {
        if(rows==null||rows.isEmpty()||requestKey==null||requestKey.isBlank()||requestKey.length()>200||reason==null||reason.isBlank()||reason.length()>1000)throw ApiException.bad("行情修订须包含行、请求键和原因");
        var normalized=new ArrayList<Map<String,Object>>();var keys=new HashSet<String>();
        for(var row:rows){long fid=Db.id(row.get("fundClassId"));var date=LocalDate.parse(Db.text(row,"marketDate"));var nav=Db.positive(row,"nav",6);if(!keys.add(fid+":"+date))throw ApiException.bad("同一修订集合不能重复产品日期");normalized.add(new TreeMap<>(Map.of("fundClassId",fid,"marketDate",date.toString(),"nav",nav.toPlainString())));}
        normalized.sort(Comparator.comparingLong((Map<String,Object> r)->Db.id(r.get("fundClassId"))).thenComparing(r->r.get("marketDate").toString()));
        String fingerprint=TradeService.fingerprint(Map.of("rows",normalized,"reason",reason));
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",actorId);
        var old=db.rows("SELECT * FROM nav_revision_sets WHERE actor_id=? AND request_key=?",actorId,requestKey);
        if(!old.isEmpty()){if(!fingerprint.equals(old.getFirst().get("fingerprint")))throw ApiException.conflict("请求键已用于另一行情修订");return Db.id(old.getFirst().get("id"));}
        long id=db.insert("INSERT INTO nav_revision_sets(request_key,fingerprint,reason,actor_id) VALUES(?,?,?,?)",requestKey,fingerprint,reason,actorId);
        for(var row:normalized){if("MONEY".equals(db.one("SELECT type FROM fund_classes WHERE id=?",row.get("fundClassId")).get("type")))throw ApiException.bad("货币基金净值固定为1，不能通过普通行情修订改变份额价格");var nav=db.one("SELECT * FROM nav_records WHERE fund_class_id=? AND date=? AND published=TRUE",row.get("fundClassId"),row.get("marketDate"));db.insert("INSERT INTO nav_revision_items(revision_set_id,fund_class_id,market_date,previous_nav,nav,previous_revision_id) VALUES(?,?,?,?,?,?)",id,row.get("fundClassId"),row.get("marketDate"),nav.get("nav"),row.get("nav"),nav.get("activeRevisionId"));}
        return id;
    }
}
