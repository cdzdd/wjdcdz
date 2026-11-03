package com.hengsheng.fund;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A bounded, read-only assistant bridge. It never forwards a browser identity or cookie. */
@Component
public class AssistantClient {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);
    private final URI origin;
    private final String key;
    private final HttpClient http;
    private final Semaphore permits = new Semaphore(2);
    private final ObjectMapper json = new ObjectMapper();

    public AssistantClient(@Value("${fund.assistant.enabled:false}") boolean enabled,
            @Value("${fund.assistant.base-url:http://knowledge-qa-api:8000}") String baseUrl,
            @Value("${fund.assistant.service-key:}") String key) {
        this.key = key;
        this.origin = enabled && key != null && key.matches("[A-Za-z0-9_-]{32,256}") ? safeOrigin(baseUrl) : null;
        this.http = origin == null ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static URI safeOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getPort() == 0 || uri.getPort() > 65535) return null;
            boolean privateHttp = "http".equals(uri.getScheme()) && Set.of("knowledge-qa-api", "localhost", "127.0.0.1").contains(uri.getHost());
            return privateHttp || "https".equals(uri.getScheme()) ? uri : null;
        } catch (IllegalArgumentException | NullPointerException ignored) { return null; }
    }

    public ResponseEntity<Map<String, Object>> status(long owner) {
        if (origin == null) return unavailable("disabled");
        try { return request(owner, "GET", "/status", null); }
        catch (ApiException error) { return unavailable(error.status == 429 ? "busy" : "offline"); }
    }

    private ResponseEntity<Map<String, Object>> unavailable(String reason) {
        return ResponseEntity.ok(Map.of("available", false, "reason", reason, "activeRequests", 0, "capacity", 0, "queueDepth", 0));
    }

    public ResponseEntity<Map<String, Object>> request(long owner, String method, String path, Map<String, Object> body) {
        if (origin == null) throw new ApiException(503, "ASSISTANT_DISABLED", "业务助手尚未启用，基金业务可正常使用");
        if (!permits.tryAcquire()) throw new ApiException(429, "ASSISTANT_BUSY", "业务助手请求较多，请稍后重试");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(origin.resolve("/internal/fund-assistant" + path)).timeout(RESPONSE_TIMEOUT)
                    .header("Authorization", "Bearer " + key).header("X-Fund-User-Id", Long.toString(owner))
                    .header("Accept", "application/json");
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            if (body != null) builder.header("Content-Type", "application/json");
            pending = http.sendAsync(builder.method(method, publisher).build(), info -> new LimitedBody());
            var response = pending.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (!Set.of(200, 201, 202).contains(status)) throw upstreamError(status);
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                throw invalidResponse();
            Map<String, Object> data = json.readValue(response.body(), Map.class);
            if (data == null) throw invalidResponse();
            return ResponseEntity.status(status).body(data);
        } catch (TimeoutException error) {
            throw new ApiException(504, "ASSISTANT_TIMEOUT", "业务助手连接超时，请稍后重试，基金业务不受影响");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "ASSISTANT_UNAVAILABLE", "业务助手暂时不可用，基金业务可正常使用");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ResponseTooLarge) throw new ApiException(502, "ASSISTANT_RESPONSE_TOO_LARGE", "业务助手响应过大，请缩短会话后重试");
            if (cause instanceof java.net.http.HttpTimeoutException)
                throw new ApiException(504, "ASSISTANT_TIMEOUT", "业务助手连接超时，请稍后重试，基金业务不受影响");
            throw new ApiException(503, "ASSISTANT_OFFLINE", "业务助手暂时离线，基金业务可正常使用");
        } catch (ApiException error) { throw error; }
        catch (RuntimeException error) { throw invalidResponse(); }
        finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            permits.release();
        }
    }

    private static ApiException invalidResponse() {
        return new ApiException(502, "ASSISTANT_INVALID_RESPONSE", "业务助手响应暂时无法读取，请稍后重试");
    }

    private static ApiException upstreamError(int status) {
        return switch (status) {
            case 404 -> new ApiException(404, "ASSISTANT_NOT_FOUND", "会话或消息不存在");
            case 400, 422 -> new ApiException(400, "ASSISTANT_INVALID_REQUEST", "消息格式无效，请检查后重试");
            case 409 -> new ApiException(409, "ASSISTANT_CONFLICT", "当前会话已有请求或请求标识冲突，请刷新后重试");
            case 429 -> new ApiException(429, "ASSISTANT_BUSY", "业务助手请求较多，请稍后重试");
            case 504 -> new ApiException(504, "ASSISTANT_TIMEOUT", "业务助手处理超时，请稍后重试");
            default -> status >= 300 && status < 400 ? invalidResponse()
                    : new ApiException(503, "ASSISTANT_UNAVAILABLE", "业务助手暂时不可用，基金业务可正常使用");
        };
    }

    @PreDestroy public void close() { if (http != null) http.shutdownNow(); }

    private static final class ResponseTooLarge extends RuntimeException {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - output.size()) {
                    subscription.cancel(); result.completeExceptionally(new ResponseTooLarge()); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(output.toByteArray()); }
    }
}
