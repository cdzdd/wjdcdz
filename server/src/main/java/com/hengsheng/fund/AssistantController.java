package com.hengsheng.fund;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AccountService accounts;
    private final AssistantClient client;
    public AssistantController(AccountService accounts, AssistantClient client) { this.accounts = accounts; this.client = client; }

    @GetMapping("/status") public ResponseEntity<Map<String, Object>> status() { return client.status(accounts.userId()); }
    @GetMapping("/conversations") public ResponseEntity<Map<String, Object>> conversations() {
        return client.request(accounts.userId(), "GET", "/conversations", null);
    }
    @PostMapping("/conversations") public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> input) {
        long owner = accounts.userId(); only(input, Set.of());
        return client.request(owner, "POST", "/conversations", Map.of());
    }
    @GetMapping("/conversations/{id}") public ResponseEntity<Map<String, Object>> conversation(@PathVariable String id) {
        return client.request(accounts.userId(), "GET", "/conversations/" + uuid(id), null);
    }
    @PostMapping("/conversations/{id}/messages") public ResponseEntity<Map<String, Object>> message(@PathVariable String id, @RequestBody Map<String, Object> input) {
        long owner = accounts.userId(); only(input, Set.of("content", "requestId"));
        if (!(input.get("content") instanceof String content) || content.isBlank() || content.codePointCount(0, content.length()) > 2000)
            throw ApiException.bad("问题须为1至2000个字符");
        if (!(input.get("requestId") instanceof String requestId)) throw ApiException.bad("请求标识须为UUID");
        return client.request(owner, "POST", "/conversations/" + uuid(id) + "/messages", Map.of("content", content, "requestId", uuid(requestId)));
    }
    @PostMapping("/conversations/{id}/cancel") public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> input) {
        long owner = accounts.userId(); only(input, Set.of());
        return client.request(owner, "POST", "/conversations/" + uuid(id) + "/cancel", Map.of());
    }
    private static void only(Map<String, Object> input, Set<String> allowed) {
        if (input != null && !allowed.containsAll(input.keySet())) throw ApiException.bad("业务助手请求包含不支持的字段");
    }
    private static String uuid(String value) {
        try {
            String result = UUID.fromString(value).toString();
            if (result.equalsIgnoreCase(value)) return result;
        } catch (IllegalArgumentException ignored) { }
        throw ApiException.bad("会话或请求标识须为UUID");
    }
}
