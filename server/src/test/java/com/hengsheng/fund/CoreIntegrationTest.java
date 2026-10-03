package com.hengsheng.fund;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoreIntegrationTest {
    @Autowired Environment env;
    ObjectMapper json = new ObjectMapper();
    class Client {
        HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token = "";
        HttpResponse<String> call(String method,String path,Object body) throws Exception {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+path));
            b.header("Content-Type","application/json");
            if (!token.isEmpty()) b.header("X-CSRF-TOKEN",token);
            return http.send(b.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        Map<String,Object> map(HttpResponse<String> r) { return json.readValue(r.body(),Map.class); }
        void csrf() throws Exception { var r=call("GET","/api/auth/csrf",null);assertThat(r.statusCode()).isEqualTo(200);token=map(r).get("token").toString(); }
        void login(String u,String p) throws Exception { csrf();var r=call("POST","/api/auth/login",Map.of("username",u,"password",p));assertThat(r.statusCode()).as(r.body()).isEqualTo(200);csrf(); }
    }
    @Test void csrfOwnershipAndLogoutAreEnforced() throws Exception {
        var c = new Client();
        assertThat(c.call("POST","/api/auth/login",Map.of("username","demo","password","Demo12345!")).statusCode()).isEqualTo(403);
        c.login("demo","Demo12345!");
        assertThat(c.call("GET","/api/admin/dashboard",null).statusCode()).isEqualTo(403);
        assertThat(c.call("GET","/api/orders/9223372036854775800",null).statusCode()).isEqualTo(404);
        assertThat(c.call("POST","/api/auth/logout",Map.of()).statusCode()).isEqualTo(200);
        assertThat(c.call("GET","/api/me",null).statusCode()).isEqualTo(401);
    }
    @Test void multipleCardsIsolateCashAndFirstGrantIsIdempotent() throws Exception {
        var c = new Client();c.csrf();String username="test"+UUID.randomUUID().toString().replace("-","").substring(0,15);
        assertThat(c.call("POST","/api/auth/register",Map.of("username",username,"password","Test12345!","displayName","测试用户")).statusCode()).isEqualTo(200);
        c.login(username,"Test12345!");
        assertThat(c.call("POST","/api/account/open",Map.of()).statusCode()).isEqualTo(200);
        var bind=Map.of("bankName","模拟银行","holderName","测试用户","requestKey","bind-a");
        var a=c.map(c.call("POST","/api/bank-cards",bind));
        assertThat(a.get("available")).isEqualTo("100000.00");
        assertThat(c.map(c.call("POST","/api/bank-cards",bind)).get("id")).isEqualTo(a.get("id"));
        var b=c.map(c.call("POST","/api/bank-cards",Map.of("bankName","模拟银行","holderName","测试用户","requestKey","bind-b")));
        assertThat(b.get("available")).isEqualTo("0.00");
        assertThat(c.call("POST","/api/bank-cards/"+a.get("id")+"/unbind",Map.of()).statusCode()).isEqualTo(409);
        assertThat(c.call("POST","/api/trading-accounts/"+b.get("tradingAccountId")+"/withdrawals",Map.of("amount","1.00","requestKey","w")).statusCode()).isEqualTo(409);
        assertThat(c.call("POST","/api/trading-accounts/"+b.get("tradingAccountId")+"/top-ups",Map.of("amount","100.00","requestKey","t")).statusCode()).isEqualTo(200);
        assertThat(c.call("POST","/api/trading-accounts/"+b.get("tradingAccountId")+"/top-ups",Map.of("amount","100.00","requestKey","t")).statusCode()).isEqualTo(200);
        var portfolio=c.map(c.call("GET","/api/portfolio",null));
        assertThat(portfolio.get("totalAssets")).isEqualTo("100100.00");
        assertThat(portfolio.get("totalIncome")).isEqualTo("0.00");
        assertThat(c.call("POST","/api/trading-accounts/"+b.get("tradingAccountId")+"/withdrawals",Map.of("amount","100.00","requestKey","empty-b")).statusCode()).isEqualTo(200);
        assertThat(c.call("POST","/api/bank-cards/"+b.get("id")+"/unbind",Map.of()).statusCode()).isEqualTo(200);
        var rebind=Map.of("bankName","模拟银行","holderName","测试用户","simNumber",b.get("simNumber"),"requestKey","rebind-b");
        var rebound=c.map(c.call("POST","/api/bank-cards",rebind));assertThat(rebound.get("id")).isEqualTo(b.get("id"));assertThat(rebound.get("tradingAccountId")).isNotEqualTo(b.get("tradingAccountId"));assertThat(rebound.get("available")).isEqualTo("0.00");
        assertThat(c.map(c.call("POST","/api/bank-cards",rebind)).get("tradingAccountId")).isEqualTo(rebound.get("tradingAccountId"));
        var activeRetry=new java.util.HashMap<String,Object>(rebind);activeRetry.put("requestKey","duplicate-active");assertThat(c.call("POST","/api/bank-cards",activeRetry).statusCode()).isEqualTo(409);
        assertThat(c.map(c.call("GET","/api/portfolio",null)).get("totalAssets")).isEqualTo("100000.00");
        assertThat(c.map(c.call("POST","/api/bank-cards",Map.of("bankName","模拟银行","holderName","测试用户","requestKey","bind-b"))).get("tradingAccountId")).isEqualTo(b.get("tradingAccountId"));
        assertThat(c.call("POST","/api/bank-cards/"+b.get("id")+"/unbind",Map.of()).statusCode()).isEqualTo(200);
        var cards=json.readValue(c.call("GET","/api/bank-cards",null).body(),List.class);assertThat(cards.stream().filter(x->((Map<?,?>)x).get("id").equals(b.get("id"))).count()).isEqualTo(1);
    }
    @Test void concurrentPurchasesCannotFreezeMoreThanSourceWallet() throws Exception {
        var admin=new Client();admin.login("admin","Admin12345!");admin.call("POST","/api/admin/clock",Map.of("dateTime","2026-10-12T14:00:00"));
        var c=new Client();c.csrf();String username="race"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        c.call("POST","/api/auth/register",Map.of("username",username,"password","Test12345!","displayName","并发测试"));c.login(username,"Test12345!");c.call("POST","/api/account/open",Map.of());c.call("POST","/api/risk-assessments",Map.of("answers",java.util.Collections.nCopies(10,5)));
        var card=c.map(c.call("POST","/api/bank-cards",Map.of("bankName","模拟银行","holderName","并发测试","requestKey","a")));
        var first=Map.of("type","PURCHASE","fundClassId",1,"tradingAccountId",card.get("tradingAccountId"),"bankCardId",card.get("id"),"bindingVersion",1,"amount","60000.00","requestKey","first");
        var second=new java.util.HashMap<String,Object>(first);second.put("requestKey","second");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var a=pool.submit(()->c.call("POST","/api/orders",first));var b=pool.submit(()->c.call("POST","/api/orders",second));assertThat(List.of(a.get().statusCode(),b.get().statusCode())).containsExactlyInAnyOrder(200,409);}
        var wallet=c.map(c.call("GET","/api/trading-accounts/"+card.get("tradingAccountId")+"/wallet",null));assertThat(wallet.get("available")).isEqualTo("40000.00");assertThat(wallet.get("frozen")).isEqualTo("60000.00");
    }
    @Test void operatorPasswordResetInvalidatesSessionsAndRequiresNewPassword() throws Exception {
        var admin=new Client();admin.login("admin","Admin12345!");String username="operator"+UUID.randomUUID().toString().replace("-","").substring(0,10);
        var operator=admin.map(admin.call("POST","/api/admin/users",Map.of("username",username,"displayName","操作员测试","password","Temp12345!","role","OPERATOR")));
        var c=new Client();c.login(username,"Temp12345!");assertThat(c.map(c.call("GET","/api/me",null)).get("mustChangePassword")).isEqualTo(true);
        assertThat(c.call("POST","/api/account/open",Map.of()).statusCode()).isEqualTo(403);
        assertThat(c.call("POST","/api/account/password",Map.of("oldPassword","Temp12345!","newPassword","New12345!")).statusCode()).isEqualTo(200);
        assertThat(c.call("GET","/api/admin/dashboard",null).statusCode()).isEqualTo(200);assertThat(c.call("GET","/api/admin/audit",null).statusCode()).isEqualTo(403);
        assertThat(admin.call("POST","/api/admin/users/"+operator.get("id")+"/reset-password",Map.of("newPassword","Reset12345!")).statusCode()).isEqualTo(200);
        assertThat(c.call("GET","/api/me",null).statusCode()).isEqualTo(401);c.login(username,"Reset12345!");assertThat(c.map(c.call("GET","/api/me",null)).get("mustChangePassword")).isEqualTo(true);
    }
    @Test void purchaseFreezeCancelConfirmationRedemptionAndBlockedPayoutStayInSourceAccount() throws Exception {
        var admin=new Client();admin.login("admin","Admin12345!");
        assertThat(admin.call("POST","/api/admin/clock",Map.of("dateTime","2026-10-12T14:00:00")).statusCode()).isEqualTo(200);
        var product=new java.util.HashMap<String,Object>();product.put("code","TEST"+UUID.randomUUID().toString().substring(0,12));product.put("name","交易测试基金");product.put("type","STOCK");product.put("manager","模拟管理人");product.put("registrarId",1);product.put("riskLevel",4);product.put("state","OPEN");product.put("purchaseRate","0.015");product.put("redemptionRate","0.005");product.put("minPurchase","10.00");product.put("minRedemption","1.0000");product.put("purchaseOpen",true);product.put("redemptionOpen",true);
        var fund=admin.map(admin.call("POST","/api/admin/funds",product));Object fid=fund.get("id");
        var c=new Client();c.csrf();String username="trade"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        c.call("POST","/api/auth/register",Map.of("username",username,"password","Test12345!","displayName","交易用户"));c.login(username,"Test12345!");
        c.call("POST","/api/account/open",Map.of());c.call("POST","/api/risk-assessments",Map.of("answers",java.util.Collections.nCopies(10,5)));
        var a=c.map(c.call("POST","/api/bank-cards",Map.of("bankName","模拟银行","holderName","交易用户","requestKey","a")));
        var b=c.map(c.call("POST","/api/bank-cards",Map.of("bankName","模拟银行","holderName","交易用户","requestKey","b")));
        var buy=new java.util.HashMap<String,Object>(Map.of("type","PURCHASE","fundClassId",fid,"tradingAccountId",a.get("tradingAccountId"),"bankCardId",a.get("id"),"bindingVersion",1,"amount","1000.00","requestKey","buy"));
        var order=c.map(c.call("POST","/api/orders",buy));assertThat(order.get("status")).isEqualTo("SUBMITTED");assertThat(order.get("applicationDate")).isEqualTo("2026-10-12");
        assertThat(c.map(c.call("POST","/api/orders",buy)).get("id")).isEqualTo(order.get("id"));
        buy.put("amount","1001.00");assertThat(c.call("POST","/api/orders",buy).statusCode()).isEqualTo(409);buy.put("amount","1000.00");
        var wrong=new java.util.HashMap<>(buy);wrong.put("requestKey","wrong");wrong.put("tradingAccountId",b.get("tradingAccountId"));wrong.put("bankCardId",b.get("id"));assertThat(c.call("POST","/api/orders",wrong).statusCode()).isEqualTo(409);
        c.call("PUT","/api/bank-cards/"+b.get("id")+"/default",Map.of());
        c.call("POST","/api/orders/"+order.get("id")+"/cancel",Map.of("requestKey","cancel"));assertThat(c.map(c.call("GET","/api/orders/"+order.get("id"),null)).get("status")).isEqualTo("CANCELLED");
        buy.put("requestKey","confirm");order=c.map(c.call("POST","/api/orders",buy));
        admin.call("POST","/api/admin/clock",Map.of("dateTime","2026-10-13T09:00:00"));
        admin.call("POST","/api/admin/jobs",Map.of("type","CONFIRM","date","2026-10-13"));
        assertThat(c.map(c.call("GET","/api/orders/"+order.get("id"),null)).get("status")).isEqualTo("SUBMITTED");
        admin.call("POST","/api/admin/jobs",Map.of("type","MARKET","date","2026-10-13"));
        admin.call("POST","/api/admin/jobs",Map.of("type","CONFIRM","date","2026-10-13"));
        order=c.map(c.call("GET","/api/orders/"+order.get("id"),null));assertThat(order.get("status")).isEqualTo("COMPLETED");assertThat(order.get("sourceCardId")).isEqualTo(a.get("id"));
        assertThat(c.call("POST","/api/redemption-groups",Map.of("fundClassId",fid,"requestKey","badgroup","allocations",List.of(Map.of("tradingAccountId",a.get("tradingAccountId"),"shares","10.0000"),Map.of("tradingAccountId",b.get("tradingAccountId"),"shares","1.0000")))).statusCode()).isEqualTo(409);
        var positions=(List<Map<String,Object>>)c.map(c.call("GET","/api/portfolio",null)).get("positions");assertThat(positions.getFirst().get("frozenShares")).isEqualTo("0.0000");
        var redeem=Map.of("type","REDEMPTION","fundClassId",fid,"tradingAccountId",a.get("tradingAccountId"),"shares","100.0000","requestKey","redeem");
        var sell=c.map(c.call("POST","/api/orders",redeem));assertThat(sell.get("status")).isEqualTo("SUBMITTED");
        c.call("PATCH","/api/bank-cards/"+a.get("id")+"/status",Map.of("status","DISABLED"));
        admin.call("PATCH","/api/admin/bank-cards/"+a.get("id")+"/receive-state",Map.of("state","BLOCKED","reason","模拟阻塞"));
        admin.call("POST","/api/admin/clock",Map.of("dateTime","2026-10-19T10:00:00"));
        admin.call("POST","/api/admin/jobs",Map.of("type","ALL","date","2026-10-19"));
        assertThat(c.map(c.call("GET","/api/orders/"+sell.get("id"),null)).get("status")).isEqualTo("CONFIRMED");
        assertThat(c.call("POST","/api/card-change-requests",Map.of("tradingAccountId",a.get("tradingAccountId"),"bankName","另一银行","holderName","交易用户","reason","错误换卡","requestKey","wrongchange")).statusCode()).isEqualTo(400);
        var change=c.map(c.call("POST","/api/card-change-requests",Map.of("tradingAccountId",a.get("tradingAccountId"),"bankName","模拟银行","holderName","交易用户","reason","旧卡收款受阻","requestKey","change")));
        assertThat(change.get("status")).isEqualTo("PENDING");
        assertThat(admin.call("POST","/api/admin/card-change-requests/"+change.get("id")+"/approve",Map.of("reason","已完成模拟同银行同持卡人核验，授权逐笔改付")).statusCode()).isEqualTo(200);
        admin.call("POST","/api/admin/jobs",Map.of("type","PAY","date","2026-10-19"));
        assertThat(c.map(c.call("GET","/api/orders/"+sell.get("id"),null)).get("status")).isEqualTo("COMPLETED");
        assertThat(c.map(c.call("GET","/api/orders/"+sell.get("id"),null)).get("sourceCardId")).isEqualTo(a.get("id"));
        assertThat(c.call("POST","/api/orders/"+sell.get("id")+"/cancel",Map.of()).statusCode()).isEqualTo(409);
        var other=new Client();other.login("demo","Demo12345!");assertThat(other.call("GET","/api/orders/"+sell.get("id"),null).statusCode()).isEqualTo(404);
        var reconciliation=admin.map(admin.call("GET","/api/admin/reconciliation",null));assertThat(reconciliation.get("ok")).isEqualTo(true);
    }
}
