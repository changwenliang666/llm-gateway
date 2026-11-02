package com.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实百炼联通。普通 mvn test 不跑，避免没有凭据或产生视频费用。
 * 默认只提交任务并查询一次。只有 -Dlive.upstream.await=true 才按 15 秒间隔继续查，最多 5 分钟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "live.upstream", matches = "true")
class LiveUpstreamTest {

    private static final Set<String> TASK_STATUSES = Set.of(
            "PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void qwenMaxWan2AndWan3AreReachable() throws Exception {
        assertTablesRemain();
        ResponseEntity<String> chat = post("/v1/chat/completions", """
                {"model":"qwen-max","message":"只回复一个字：好","max_tokens":16}
                """);
        assertEquals(200, chat.getStatusCode().value(), chat.getBody());
        JsonNode content = mapper.readTree(chat.getBody()).path("choices").path(0).path("message").path("content");
        assertTrue(content.isTextual() && !content.asText().isBlank(), chat.getBody());

        int modelsBefore = count("models");
        int keysBefore = count("account_keys");
        String wan2Task = createAndQuery("wan2.0", """
                {"model":"wan2.0","input":{"prompt":"一只小猫在草地上走路"},"parameters":{"size":"1280*720","duration":2,"prompt_extend":false}}
                """);
        String wan3Task = createAndQuery("wan3.0", """
                {"model":"wan3.0","input":{"prompt":"一只小猫在草地上走路"},"parameters":{"resolution":"480P","ratio":"16:9","duration":2,"prompt_extend":false}}
                """);
        assertFalse(wan2Task.isBlank());
        assertFalse(wan3Task.isBlank());
        assertEquals("wan2.0", storedModel(wan2Task));
        assertEquals("wan3.0", storedModel(wan3Task));
        assertFalse(storedStatus(wan2Task).isBlank());
        assertFalse(storedStatus(wan3Task).isBlank());
        assertEquals(modelsBefore, count("models"));
        assertEquals(keysBefore, count("account_keys"));

        Integer routed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM models
                WHERE model_name IN ('qwen-max', 'wan2.0', 'wan3.0')
                  AND base_url LIKE '%dashscope.aliyuncs.com%'
                """, Integer.class);
        assertEquals(3, routed);
        assertTablesRemain();
    }

    private String createAndQuery(String model, String body) throws Exception {
        ResponseEntity<String> created = post("/v1/video/synthesis", body);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        JsonNode taskId = mapper.readTree(created.getBody()).path("output").path("task_id");
        assertTrue(taskId.isTextual() && !taskId.asText().isBlank(), created.getBody());
        assertFalse(created.getBody().contains("video_url"));
        ResponseEntity<String> queried = rest.getForEntity("/v1/tasks/{taskId}?model={model}", String.class, taskId.asText(), model);
        assertEquals(200, queried.getStatusCode().value(), queried.getBody());
        String status = mapper.readTree(queried.getBody()).path("output").path("task_status").asText();
        assertTrue(TASK_STATUSES.contains(status), queried.getBody());
        if (Boolean.getBoolean("live.upstream.await")) {
            awaitTerminal(model, taskId.asText(), status);
        }
        return taskId.asText();
    }

    /** 成片与否由调用方决定。默认冒烟不等待，避免每次联通都占满 5 分钟。 */
    private void awaitTerminal(String model, String taskId, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (!isTerminal(status) && System.nanoTime() < deadline) {
            Thread.sleep(TimeUnit.SECONDS.toMillis(15));
            ResponseEntity<String> queried = rest.getForEntity(
                    "/v1/tasks/{taskId}?model={model}", String.class, taskId, model);
            assertEquals(200, queried.getStatusCode().value(), queried.getBody());
            status = mapper.readTree(queried.getBody()).path("output").path("task_status").asText();
            assertTrue(TASK_STATUSES.contains(status), queried.getBody());
        }
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private String storedModel(String taskId) {
        return jdbc.queryForObject("SELECT model_name FROM model_tasks WHERE task_id = ?", String.class, taskId);
    }

    private String storedStatus(String taskId) {
        return jdbc.queryForObject("SELECT task_status FROM model_tasks WHERE task_id = ?", String.class, taskId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertTablesRemain() {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('providers', 'provider_accounts', 'account_keys', 'models', 'model_accounts', 'model_tasks')
                """, Integer.class);
        assertEquals(6, tables);
    }
}
