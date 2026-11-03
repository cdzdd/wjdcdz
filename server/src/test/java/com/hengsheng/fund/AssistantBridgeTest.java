package com.hengsheng.fund;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP upstream and actual fund SecurityConfig/AccountGuard; no business database is used. */
class AssistantBridgeTest {
    private static final String KEY = "test_only_assistant_service_key_0123456789";
    private static final String ID = "83161d5b-c09f-4358-9144-d95c4e678fef";
    private HttpServer upstream;
    private ExecutorService serverThreads;
    private AnnotationConfigWebApplicationContext context;
    private AssistantClient client;
    private MockMvc mvc;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws Exception; }

    @BeforeEach void setup() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool(); upstream.setExecutor(serverThreads);
        handler.set(exchange -> respond(exchange, 200, "{\"available\":true,\"reason\":\"ready\"}"));
        upstream.createContext("/", exchange -> {
            calls.incrementAndGet();
            try { handler.get().handle(exchange); }
            catch (Exception ignored) { exchange.close(); }
        });
        upstream.start();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("assistant-test", Map.of(
                "assistant-test-url", "http://127.0.0.1:" + upstream.getAddress().getPort())));
        context.register(TestConfig.class); context.refresh();
        client = context.getBean(AssistantClient.class);
        when(context.getBean(AccountService.class).userId()).thenAnswer(invocation -> switch (SecurityContextHolder.getContext().getAuthentication().getName()) {
            case "alice" -> 11L; case "bob" -> 22L; default -> 33L;
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (upstream != null) upstream.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }
    private static void respond(HttpExchange exchange, int status, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
    private static String message() { return "{\"content\":\"如何申购？\",\"requestId\":\"" + ID + "\"}"; }

    @Test void fundAuthenticationCsrfAndTemporaryPasswordGuardRemainRequired() throws Exception {
        mvc.perform(get("/api/assistant/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/conversations").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/assistant/conversations").with(user("temp")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/assistant/status").with(user("disabled"))).andExpect(status().isForbidden());
        assertThat(calls.get()).isZero();
    }

    @Test void identityIsDerivedFromFundSessionAndBrowserHeadersAreNotForwarded() throws Exception {
        var owner = new AtomicReference<String>(); var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>(); var received = new AtomicReference<String>();
        handler.set(exchange -> {
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/internal/fund-assistant/conversations/" + ID + "/messages");
            owner.set(exchange.getRequestHeaders().getFirst("X-Fund-User-Id"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"conversationId\":\"" + ID + "\",\"requestId\":\"" + ID + "\",\"status\":\"queued\"}");
        });
        mvc.perform(post("/api/assistant/conversations/" + ID + "/messages").with(user("alice")).with(csrf())
                .header("X-Fund-User-Id", "999").header("Authorization", "Bearer browser-key")
                .header("Cookie", "qa_session=must-not-leave-fund").contentType("application/json").content(message()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued"));
        assertThat(owner.get()).isEqualTo("11"); assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        assertThat(cookie.get()).isNull(); assertThat(received.get()).contains("如何申购？", "requestId").doesNotContain("999", "tenant", "knowledge");
    }

    @Test void extraFieldsBadIdentifiersAndOversizedQuestionsNeverReachQa() throws Exception {
        for (String body : new String[]{message().replace("}", ",\"userId\":999}"), "{\"content\":\"hi\",\"requestId\":\"bad\"}",
                "{\"content\":\"" + "长".repeat(2001) + "\",\"requestId\":\"" + ID + "\"}", "{\"content\":\" \"}"}) {
            mvc.perform(post("/api/assistant/conversations/" + ID + "/messages").with(user("alice")).with(csrf())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/assistant/conversations/1-1-1-1-1").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/conversations").with(user("alice")).with(csrf())
                .contentType("application/json").content("{\"knowledgeBaseId\":\"other\"}")).andExpect(status().isBadRequest());
        assertThat(calls.get()).isZero();
    }

    @Test void sameConversationUsesEachAuthenticatedOwnerAndUpstreamNotFoundIsSanitized() throws Exception {
        handler.set(exchange -> {
            if ("11".equals(exchange.getRequestHeaders().getFirst("X-Fund-User-Id"))) respond(exchange, 200, "{\"id\":\"" + ID + "\",\"messages\":[]}");
            else respond(exchange, 404, "{\"detail\":\"private-upstream-detail\"}");
        });
        mvc.perform(get("/api/assistant/conversations/" + ID).with(user("alice"))).andExpect(status().isOk());
        mvc.perform(get("/api/assistant/conversations/" + ID).with(user("bob")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSISTANT_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-upstream-detail"))));
    }

    @Test void fixedConversationRoutesPreserveQaContractWithoutRemoteCookies() throws Exception {
        handler.set(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/conversations")) respond(exchange, 201, "{\"id\":\"" + ID + "\"}");
            else if (path.endsWith("/cancel")) respond(exchange, 200, "{\"status\":\"cancelled\"}");
            else respond(exchange, 200, "{\"items\":[],\"total\":0}");
        });
        mvc.perform(post("/api/assistant/conversations").with(user("alice")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(ID));
        mvc.perform(get("/api/assistant/conversations").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/assistant/conversations/" + ID + "/cancel").with(user("alice")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test void disabledAndInvalidConfigurationsDoNotFailConstructionOrHealth() throws Exception {
        for (AssistantClient disabled : new AssistantClient[]{new AssistantClient(false, "bad-url", KEY),
                new AssistantClient(true, "http://127.0.0.1:1", ""), new AssistantClient(true, "http://untrusted.example", KEY),
                new AssistantClient(true, "https://user:password@example.com", KEY), new AssistantClient(true, "https://example.com/path?key=secret", KEY)}) {
            assertThat(disabled.status(11).getBody()).containsEntry("available", false).containsEntry("reason", "disabled");
            assertThatThrownBy(() -> disabled.request(11, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code).isEqualTo("ASSISTANT_DISABLED"));
            disabled.close();
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(calls.get()).isZero();
    }

    @Test void offlineAssistantStillAllowsFundPortfolioAndReturnsUsefulStatus() throws Exception {
        upstream.stop(0);
        mvc.perform(get("/api/assistant/status").with(user("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.reason").value("offline"));
        mvc.perform(get("/api/assistant/conversations").with(user("alice"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_OFFLINE"));
        mvc.perform(get("/api/portfolio").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalAssets").value("100000.00"));
    }

    @Test void rateLimitAndUpstreamCredentialFailuresAreMappedWithoutLeakingBody() {
        for (int upstreamStatus : new int[]{401, 403, 429, 500, 503, 504}) {
            handler.set(exchange -> respond(exchange, upstreamStatus, "{\"detail\":\"" + KEY + "\"}"));
            assertThatThrownBy(() -> client.request(11, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getMessage()).doesNotContain(KEY);
                assertThat(error.status).isEqualTo(upstreamStatus == 429 ? 429 : upstreamStatus == 504 ? 504 : 503);
                assertThat(error.code).startsWith("ASSISTANT_");
            });
        }
    }

    @Test void redirectsAreNeverFollowed() {
        handler.set(exchange -> { exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/unexpected"); respond(exchange, 302, "{}"); });
        assertThatThrownBy(() -> client.request(11, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code).isEqualTo("ASSISTANT_INVALID_RESPONSE"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void responseBodiesAreBoundedBeforeJsonParsing() {
        handler.set(exchange -> respond(exchange, 200, "{\"content\":\"" + "x".repeat(AssistantClient.MAX_RESPONSE_BYTES) + "\"}"));
        assertThatThrownBy(() -> client.request(11, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class,
                error -> { assertThat(error.status).isEqualTo(502); assertThat(error.code).isEqualTo("ASSISTANT_RESPONSE_TOO_LARGE"); });
    }

    @Test void malformedOrWrongTypeJsonIsSanitized() {
        for (String body : new String[]{"not-json-with-private-details", "[]", "null"}) {
            handler.set(exchange -> respond(exchange, 200, body));
            assertThatThrownBy(() -> client.request(11, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code).isEqualTo("ASSISTANT_INVALID_RESPONSE"));
        }
    }

    @Test void slowResponseHeadersTimeOutWithoutRetry() {
        handler.set(exchange -> { Thread.sleep(6000); respond(exchange, 200, "{}"); });
        long start = System.nanoTime();
        assertTimeout(() -> client.request(11, "GET", "/conversations", null));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void slowResponseBodyAlsoHasWholeResponseDeadline() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); Thread.sleep(6000); exchange.close();
        });
        long start = System.nanoTime(); assertTimeout(() -> client.request(11, "GET", "/conversations", null));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(calls.get()).isEqualTo(1);
    }
    private static void assertTimeout(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ApiException.class,
                error -> { assertThat(error.status).isEqualTo(504); assertThat(error.code).isEqualTo("ASSISTANT_TIMEOUT"); });
    }

    @Test void thirdAssistantRequestIsRejectedImmediatelyWhileFundEndpointsRemainUsable() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        handler.set(exchange -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); respond(exchange, 200, "{}"); });
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> client.request(11, "GET", "/conversations", null));
            var second = callers.submit(() -> client.request(22, "GET", "/conversations", null));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> client.request(33, "GET", "/conversations", null)).isInstanceOfSatisfying(ApiException.class,
                        error -> { assertThat(error.status).isEqualTo(429); assertThat(error.code).isEqualTo("ASSISTANT_BUSY"); });
                assertThat(client.status(33).getBody()).containsEntry("available", false).containsEntry("reason", "busy");
                mvc.perform(get("/api/portfolio").with(user("alice"))).andExpect(status().isOk());
                mvc.perform(get("/actuator/health")).andExpect(status().isOk());
                assertThat(calls.get()).isEqualTo(2);
            } finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(second.get(3, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(client.request(11, "GET", "/conversations", null).getStatusCode().value()).isEqualTo(200);
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class, AccountGuard.class, AssistantController.class, ApiErrors.class, OtherFundEndpoints.class})
    static class TestConfig {
        @Bean AccountService accounts() { return mock(AccountService.class); }
        @Bean AssistantClient client(Environment environment) { return new AssistantClient(true, environment.getRequiredProperty("assistant-test-url"), KEY); }
        @Bean Db db() { return new Db(mock(JdbcTemplate.class)) {
            @Override public Map<String, Object> one(String sql, Object... args) {
                if (!sql.equals("SELECT enabled,must_change_password FROM users WHERE username=?")) throw new AssertionError("Unexpected business SQL");
                return Map.of("enabled", !args[0].equals("disabled"), "mustChangePassword", args[0].equals("temp"));
            }
        }; }
    }
    @RestController static class OtherFundEndpoints {
        @GetMapping("/actuator/health") Map<String, Object> health() { return Map.of("status", "UP"); }
        @GetMapping("/api/portfolio") Map<String, Object> portfolio() { return Map.of("totalAssets", "100000.00"); }
    }
}
