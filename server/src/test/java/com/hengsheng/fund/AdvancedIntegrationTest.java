package com.hengsheng.fund;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdvancedIntegrationTest {
    @Autowired Environment env;
    @Autowired Db db;
    @Autowired TradeService trades;
    @Autowired AccountService accounts;
    final ObjectMapper json=new ObjectMapper();
    class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token="";
        HttpResponse<String> call(String method,String path,Object data) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path)).header("Content-Type","application/json");
            if(!token.isEmpty()) request.header("X-CSRF-TOKEN",token);
            return http.send(request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
        }
        Map<String,Object> map(String method,String path,Object data) throws Exception {
            var response=call(method,path,data);assertThat(response.statusCode()).as(path+" "+response.body()).isEqualTo(200);
            return json.readValue(response.body(),Map.class);
        }
        List<Map<String,Object>> list(String path) throws Exception {
            var response=call("GET",path,null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return json.readValue(response.body(),List.class);
        }
        void csrf() throws Exception { token=map("GET","/auth/csrf",null).get("token").toString(); }
        void login(String user,String password) throws Exception { csrf();map("POST","/auth/login",Map.of("username",user,"password",password));csrf(); }
    }
    Client admin(String clock) throws Exception { var c=new Client();c.login("admin","Admin12345!");c.map("POST","/admin/clock",Map.of("dateTime",clock));return c; }
    Client investor() throws Exception {
        var c=new Client();c.csrf();String name="adv"+UUID.randomUUID().toString().replace("-","").substring(0,14);
        c.map("POST","/auth/register",Map.of("username",name,"password","Test12345!","displayName","扩展测试"));c.login(name,"Test12345!");
        c.map("POST","/account/open",Map.of());c.map("POST","/risk-assessments",Map.of("answers",Collections.nCopies(10,5)));return c;
    }
    Map<String,Object> card(Client c,String key) throws Exception { return c.map("POST","/bank-cards",Map.of("bankName","模拟银行","holderName","扩展测试","requestKey",key)); }
    long product(Client admin,String state) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("code","ADV"+UUID.randomUUID().toString().substring(0,12));body.put("name","扩展业务验证基金");
        body.put("type","BOND");body.put("manager","扩展管理人");body.put("registrarId",1);body.put("riskLevel",2);body.put("state",state);
        body.put("purchaseRate","0");body.put("redemptionRate","0");body.put("minPurchase","10");body.put("minRedemption","1");body.put("purchaseOpen",true);body.put("redemptionOpen",true);
        return Long.parseLong(admin.map("POST","/admin/funds",body).get("id").toString());
    }
    Map<String,Object> request(Map<String,Object> card,long fund,String amount,String key) {
        return new LinkedHashMap<>(Map.of("fundClassId",fund,"tradingAccountId",card.get("tradingAccountId"),"bankCardId",card.get("id"),"bindingVersion",card.get("bindingVersion"),"amount",amount,"requestKey",key));
    }
    void time(Client admin,String value) throws Exception { admin.map("POST","/admin/clock",Map.of("dateTime",value)); }
    void job(Client admin,String type,String date) throws Exception { admin.map("POST","/admin/jobs",Map.of("type",type,"date",date)); }
    void nav(Client admin,long fund,String date,String nav) throws Exception { admin.map("POST","/admin/navs",Map.of("fundClassId",fund,"date",date,"nav",nav)); }

    @Test void failedOfferingRefundKeepsOriginalAccountAndIsIdempotent() throws Exception {
        var admin=admin("2026-10-12T14:00:00");long fund=product(admin,"RAISING");var user=investor();var a=card(user,"a");var b=card(user,"b");
        var request=request(a,fund,"1200.00","offer");var order=user.map("POST","/offering-orders",request);
        assertThat(user.map("POST","/offering-orders",request).get("id")).isEqualTo(order.get("id"));
        user.map("PUT","/bank-cards/"+b.get("id")+"/default",Map.of());
        time(admin,"2026-10-13T10:00:00");var result=Map.of("outcome","FAILED","endDate","2026-10-13","initialNav","1.000000","interestRate","0");
        admin.map("POST","/admin/offerings/"+fund+"/finalize",result);job(admin,"PAY","2026-10-13");
        admin.map("POST","/admin/offerings/"+fund+"/finalize",result);job(admin,"PAY","2026-10-13");
        var cards=user.list("/bank-cards");assertThat(cards.stream().filter(r->r.get("id").equals(a.get("id"))).findFirst().orElseThrow().get("available")).isEqualTo("100000.00");
        assertThat(cards.stream().filter(r->r.get("id").equals(b.get("id"))).findFirst().orElseThrow().get("available")).isEqualTo("0.00");
    }
    @Test void sipKeepsExplicitAccountAndCreatesOnlyOneOrderPerPeriod() throws Exception {
        var admin=admin("2026-10-12T10:00:00");long fund=product(admin,"OPEN");var user=investor();var a=card(user,"a");var b=card(user,"b");
        var body=request(a,fund,"100.00","plan");body.put("frequency","MONTHLY");body.put("day",12);body.put("startDate","2026-10-12");
        var plan=user.map("POST","/sip-plans",body);user.map("PUT","/bank-cards/"+b.get("id")+"/default",Map.of());
        job(admin,"SIP","2026-10-12");job(admin,"SIP","2026-10-12");
        var runs=user.list("/sip-runs");assertThat(runs.stream().filter(r->r.get("planId").equals(plan.get("id")))).hasSize(1);
        var orders=user.list("/orders");assertThat(orders).hasSize(1);assertThat(orders.getFirst().get("tradingAccountId")).isEqualTo(a.get("tradingAccountId"));
    }
    @Test void cashDividendPaysBothSourcesAndDoesNotCountExRightsAsLoss() throws Exception {
        var admin=admin("2026-10-12T14:00:00");long fund=product(admin,"OPEN");var user=investor();var a=card(user,"a");var b=card(user,"b");
        user.map("POST","/trading-accounts/"+b.get("tradingAccountId")+"/top-ups",Map.of("amount","60.00","requestKey","top"));
        var buyA=request(a,fund,"120.00","buy-a");buyA.put("type","PURCHASE");var buyB=request(b,fund,"60.00","buy-b");buyB.put("type","PURCHASE");
        user.map("POST","/orders",buyA);user.map("POST","/orders",buyB);
        time(admin,"2026-10-12T16:00:00");nav(admin,fund,"2026-10-12","1.200000");
        time(admin,"2026-10-13T10:00:00");job(admin,"CONFIRM","2026-10-13");
        var event=admin.map("POST","/admin/dividends",Map.of("fundClassId",fund,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-14","perShare","0.10"));
        time(admin,"2026-10-14T16:00:00");nav(admin,fund,"2026-10-14","1.100000");
        admin.map("POST","/admin/dividends/"+event.get("id")+"/snapshot",Map.of());admin.map("POST","/admin/dividends/"+event.get("id")+"/execute",Map.of());
        job(admin,"PAY","2026-10-14");job(admin,"PAY","2026-10-14");
        var rights=user.list("/dividends");assertThat(rights).hasSize(2);
        assertThat(rights.stream().map(r->r.get("amount"))).containsExactlyInAnyOrder("10.00","5.00");
        assertThat(user.map("GET","/portfolio",null).get("totalIncome")).isEqualTo("0.00");
    }
    @Test void conversionRetainsSourceAndCreatesTargetOnlyOnce() throws Exception {
        var admin=admin("2026-10-12T14:00:00");long source=product(admin,"OPEN"),target=product(admin,"OPEN");var user=investor();var card=card(user,"a");
        var buy=request(card,source,"1200.00","buy");buy.put("type","PURCHASE");user.map("POST","/orders",buy);
        time(admin,"2026-10-12T16:00:00");nav(admin,source,"2026-10-12","1.200000");
        time(admin,"2026-10-13T10:00:00");job(admin,"CONFIRM","2026-10-13");
        admin.map("POST","/admin/conversion-pairs",Map.of("sourceClassId",source,"targetClassId",target,"enabled",true));
        time(admin,"2026-10-20T14:00:00");var input=Map.of("tradingAccountId",card.get("tradingAccountId"),"sourceClassId",source,"targetClassId",target,"shares","300.0000","requestKey","convert");
        var converted=user.map("POST","/conversions",input);assertThat(user.map("POST","/conversions",input).get("id")).isEqualTo(converted.get("id"));
        time(admin,"2026-10-20T16:00:00");nav(admin,source,"2026-10-20","1.200000");nav(admin,target,"2026-10-20","1.800000");
        time(admin,"2026-10-21T10:00:00");job(admin,"CONFIRM","2026-10-21");job(admin,"CONFIRM","2026-10-21");
        var records=user.list("/conversions");assertThat(records.getFirst().get("targetShares")).isEqualTo("200.0000");
        assertThat(records.getFirst().get("status")).isEqualTo("COMPLETED");
    }
    @Test void historicConfirmedRightsDoNotDependOnLedgerPostingTime() throws Exception {
        var admin=admin("2026-10-13T10:00:00");long fund=product(admin,"OPEN");var user=investor();var card=card(user,"a");
        var event=admin.map("POST","/admin/dividends",Map.of("fundClassId",fund,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-16","perShare","0.10"));
        long uid=Long.parseLong(user.map("GET","/me",null).get("id").toString());long aid=Long.parseLong(card.get("tradingAccountId").toString());
        long fa=accounts.linkFundAccount(uid,aid,fund);
        time(admin,"2026-10-16T10:00:00");
        trades.addLot(uid,aid,fund,Long.parseLong(card.get("id").toString()),fa,new java.math.BigDecimal("100.0000"),new java.math.BigDecimal("120.00"),java.time.LocalDate.parse("2026-10-13"),"HISTORIC_CONFIRM","recorded-earlier");
        admin.map("POST","/admin/dividends/"+event.get("id")+"/snapshot",Map.of());
        var rights=user.list("/dividends");assertThat(rights).hasSize(1);assertThat(rights.getFirst().get("amount")).isEqualTo("10.00");
    }
    @Test void changingDividendPreferenceAfterTheDeadlineCannotChangeCurrentRights() throws Exception {
        var admin=admin("2026-10-13T10:00:00");long fund=product(admin,"OPEN");var user=investor();card(user,"a");
        admin.map("POST","/admin/dividends",Map.of("fundClassId",fund,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-14","perShare","0.10"));
        user.map("PUT","/dividend-preferences/"+fund,Map.of("mode","CASH"));
        time(admin,"2026-10-13T15:01:00");
        assertThat(user.call("PUT","/dividend-preferences/"+fund,Map.of("mode","REINVEST")).statusCode()).isBetween(400,499);
    }
    @Test void cashCorrectionReferencesOriginalEntryAndCannotBeAppliedTwice() throws Exception {
        var admin=admin("2026-10-13T10:00:00");var user=investor();var card=card(user,"a");
        user.map("POST","/trading-accounts/"+card.get("tradingAccountId")+"/top-ups",Map.of("amount","100.00","requestKey","topup"));
        var entry=user.list("/cash-entries").stream().filter(row->"TOPUP".equals(row.get("sourceType"))).findFirst().orElseThrow();
        var body=Map.of("originalEntryId",entry.get("id"),"availableDelta","-10.00","reason","模拟充值多记10元，更正差额","requestKey","correction-"+UUID.randomUUID());
        assertThat(user.call("POST","/admin/corrections",body).statusCode()).isEqualTo(403);
        var first=admin.map("POST","/admin/corrections",body);
        assertThat(admin.map("POST","/admin/corrections",body).get("id")).isEqualTo(first.get("id"));
        var assets=user.map("GET","/portfolio",null);assertThat(assets.get("totalAssets")).isEqualTo("100090.00");assertThat(assets.get("totalIncome")).isEqualTo("0.00");
        assertThat(user.list("/cash-entries").stream().filter(row->row.get("id").equals(entry.get("id"))).findFirst().orElseThrow().get("amount")).isEqualTo("100.00");
    }
    @Test void unregisteredDividendRightsPreventClosingAnOtherwiseEmptyCard() throws Exception {
        var admin=admin("2026-10-13T10:00:00");long fund=product(admin,"OPEN");var user=investor();var card=card(user,"a");
        long uid=Long.parseLong(user.map("GET","/me",null).get("id").toString()),aid=Long.parseLong(card.get("tradingAccountId").toString());
        long fa=accounts.linkFundAccount(uid,aid,fund);
        trades.addLot(uid,aid,fund,Long.parseLong(card.get("id").toString()),fa,new java.math.BigDecimal("100.0000"),new java.math.BigDecimal("120.00"),java.time.LocalDate.parse("2026-10-12"),"HISTORIC_CONFIRM","rights");
        admin.map("POST","/admin/dividends",Map.of("fundClassId",fund,"recordDate","2026-10-13","exDate","2026-10-14","paymentDate","2026-10-16","perShare","0.10"));
        time(admin,"2026-10-14T10:00:00");
        trades.consumeLots(trades.reserveLots(uid,aid,fund,new java.math.BigDecimal("100.0000")),"SETTLED_REDEMPTION","fixture");
        user.map("POST","/trading-accounts/"+aid+"/withdrawals",Map.of("amount","100000.00","requestKey","empty"));
        assertThat(user.call("POST","/bank-cards/"+card.get("id")+"/unbind",Map.of()).statusCode()).isEqualTo(409);
    }
    @Test void aWholeTinyRemainderCanBeRedeemedWithoutAddingMoreMoney() throws Exception {
        var admin=admin("2026-10-13T10:00:00");long fund=product(admin,"OPEN");var user=investor();var card=card(user,"a");
        long uid=Long.parseLong(user.map("GET","/me",null).get("id").toString()),aid=Long.parseLong(card.get("tradingAccountId").toString());
        long fa=accounts.linkFundAccount(uid,aid,fund);
        trades.addLot(uid,aid,fund,Long.parseLong(card.get("id").toString()),fa,new java.math.BigDecimal("0.1234"),new java.math.BigDecimal("0.12"),java.time.LocalDate.parse("2026-10-12"),"DIVIDEND","tiny");
        var order=user.map("POST","/orders",Map.of("type","REDEMPTION","fundClassId",fund,"tradingAccountId",aid,"shares","0.1234","requestKey","exit"));
        assertThat(order.get("status")).isEqualTo("SUBMITTED");
    }
}
