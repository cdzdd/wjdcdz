package com.hengsheng.fund;

import java.net.*;
import java.net.http.*;
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
class ApplicationExportIntegrationTest {
    @Autowired Environment env;
    @Autowired Db db;
    final ObjectMapper json=new ObjectMapper();
    @Test void dailyExportIsAnAsynchronousAuthorizedTask() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String base="http://127.0.0.1:"+env.getProperty("local.server.port")+"/api";
        var csrf=json.readValue(client.send(HttpRequest.newBuilder(URI.create(base+"/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body(),Map.class);
        var login=HttpRequest.newBuilder(URI.create(base+"/auth/login")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf.get("token").toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username","admin","password","Admin12345!")))).build();
        assertThat(client.send(login,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        csrf=json.readValue(client.send(HttpRequest.newBuilder(URI.create(base+"/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString()).body(),Map.class);
        db.update("UPDATE app_settings SET value='2026-11-16T16:00:00' WHERE name='demo_clock'");
        var request=HttpRequest.newBuilder(URI.create(base+"/admin/application-exports")).header("Content-Type","application/json").header("X-CSRF-TOKEN",csrf.get("token").toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("date","2026-11-16","registrarId",1,"requestKey","export-"+UUID.randomUUID())))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        var result=json.readValue(response.body(),Map.class);
        assertThat(result).containsKeys("exportId","runId");
    }
}
