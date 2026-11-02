package com.llmgateway;

import com.llmgateway.support.DockerConditions;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker 可用时用临时 MySQL 和 Redis 把选路、聊天重试和万相查询串起来。
 * 本机没有 Docker 时整类跳过，单元测试仍然可以通过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("dockerAvailable")
class GatewayFlowTest {

    private static MySQLContainer<?> mysql;
    private static GenericContainer<?> redis;
    private static MockWebServer server;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    static boolean dockerAvailable() {
        return DockerConditions.available();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        if (!DockerConditions.available()) {
            return;
        }
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36")).withDatabaseName("llmgateway");
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        mysql.start();
        redis.start();
        registry.add("MYSQL_SERVER", () -> mysql.getHost() + ":" + mysql.getMappedPort(3306));
        registry.add("MYSQL_USER", mysql::getUsername);
        registry.add("MYSQL_PASSWORD", mysql::getPassword);
        registry.add("MYSQL_DATABASE", mysql::getDatabaseName);
        registry.add("REDIS_SERVER", () -> redis.getHost() + ":" + redis.getMappedPort(6379));
        registry.add("REDIS_PASSWORD", () -> "");
    }

    @BeforeAll
    static void upstream() throws Exception {
        if (!DockerConditions.available()) {
            return;
        }
        server = new MockWebServer();
        server.start();
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    connection, new org.springframework.core.io.FileSystemResource("sql/schema.sql"));
        }
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (redis != null) {
            redis.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void chatRetryAndWanQueryUseTheDatabaseAndRedis() throws Exception {
        int providerId = insert("INSERT INTO providers (provider_name) VALUES ('it_flow')",
                "SELECT id FROM providers WHERE provider_name = 'it_flow'");
        int first = insertAccount(providerId, "it_flow_a");
        int second = insertAccount(providerId, "it_flow_b");
        insertKey(first, "a", "flow-key-a");
        insertKey(second, "b", "flow-key-b");
        String base = server.url("/upstream").toString();
        int chat = insertModel(providerId, "it-chat", "openai", "it-chat", base);
        int video = insertModel(providerId, "it-wan", "dashscope-wan", "wan2.6-t2v", base);
        link(chat, first);
        link(chat, second);
        link(video, first);

        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"slow\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"ok\"}"));
        ResponseEntity<String> chatResponse = post("/v1/chat/completions",
                "{\"model\":\"it-chat\",\"message\":\"hi\"}");
        assertEquals(200, chatResponse.getStatusCode().value(), chatResponse.getBody());
        assertEquals("Bearer flow-key-a", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer flow-key-b", server.takeRequest().getHeader("Authorization"));

        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"output\":{\"task_id\":\"flow-task\",\"task_status\":\"PENDING\"}}"));
        ResponseEntity<String> created = post("/v1/video/synthesis",
                "{\"model\":\"it-wan\",\"input\":{\"prompt\":\"猫\"}}");
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        assertTrue(created.getBody().contains("PENDING"));
        okhttp3.mockwebserver.RecordedRequest createRequest = server.takeRequest();
        assertEquals("enable", createRequest.getHeader("X-DashScope-Async"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"output\":{\"task_id\":\"flow-task\",\"task_status\":\"RUNNING\"}}"));
        ResponseEntity<String> queried = rest.getForEntity("/v1/tasks/flow-task?model=it-wan", String.class);
        assertEquals(200, queried.getStatusCode().value(), queried.getBody());
        assertTrue(queried.getBody().contains("RUNNING"));
        assertEquals("/api/v1/tasks/flow-task", server.takeRequest().getPath());
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private int insertAccount(int providerId, String name) {
        jdbc.update("INSERT INTO provider_accounts (provider_id, account_name, enabled) VALUES (?, ?, 1)", providerId, name);
        return jdbc.queryForObject("SELECT id FROM provider_accounts WHERE account_name = ?", Integer.class, name);
    }

    private void insertKey(int accountId, String name, String apiKey) {
        jdbc.update("INSERT INTO account_keys (provider_account_id, key_name, api_key, enabled) VALUES (?, ?, ?, 1)",
                accountId, name, apiKey);
    }

    private int insertModel(int providerId, String name, String adapter, String upstream, String baseUrl) {
        jdbc.update("""
                INSERT INTO models (provider_id, model_name, adapter_name, upstream_model_name, base_url, enabled)
                VALUES (?, ?, ?, ?, ?, 1)
                """, providerId, name, adapter, upstream, baseUrl);
        return jdbc.queryForObject("SELECT id FROM models WHERE model_name = ?", Integer.class, name);
    }

    private void link(int modelId, int accountId) {
        jdbc.update("INSERT INTO model_accounts (model_id, provider_account_id, enabled) VALUES (?, ?, 1)", modelId, accountId);
    }

    private int insert(String insertSql, String selectSql) {
        jdbc.update(insertSql);
        return jdbc.queryForObject(selectSql, Integer.class);
    }
}
