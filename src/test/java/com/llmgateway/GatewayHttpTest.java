package com.llmgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.GatewayException;
import com.llmgateway.error.GatewayExceptionHandler;
import com.llmgateway.gateway.ChatCompletionService;
import com.llmgateway.gateway.ModelAdmission;
import com.llmgateway.gateway.VideoSynthesisService;
import com.llmgateway.routing.AccountLoadBalancer;
import com.llmgateway.routing.AllowAllCircuitBreaker;
import com.llmgateway.routing.AllowAllRateLimiter;
import com.llmgateway.support.InMemoryModelCircuitBreaker;
import com.llmgateway.support.InMemoryModelRateLimiter;
import com.llmgateway.routing.ModelRow;
import com.llmgateway.routing.RedisKeys;
import com.llmgateway.routing.RoundRobinAccountLoadBalancer;
import com.llmgateway.routing.RouteResolver;
import com.llmgateway.routing.RotationCursor;
import com.llmgateway.routing.TokenUsage;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.InMemoryRotationCursor;
import com.llmgateway.support.InMemoryTaskBindingStore;
import com.llmgateway.support.PausingHttpServer;
import com.llmgateway.timeout.HeaderTimeoutPolicy;
import com.llmgateway.upstream.AdapterRegistry;
import com.llmgateway.upstream.UpstreamClient;
import com.llmgateway.upstream.openai.OpenAiChatAdapter;
import com.llmgateway.upstream.wan.DashScopeWanAdapter;
import com.llmgateway.web.ChatCompletionController;
import com.llmgateway.web.VideoController;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用模拟上游把规格里的转发、重试、流式和错误码串起来。
 * 不启动 Spring，也不连接真实的 MySQL、Redis 或百炼。
 */
class GatewayHttpTest {

    private static final String SECRET = "fixture-secret-key-should-not-leak";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private FakeCatalog catalog;
    private InMemoryRotationCursor cursor;
    private InMemoryTaskBindingStore bindings;
    private MockMvc mockMvc;
    private ChatCompletionService chats;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        catalog = new FakeCatalog();
        cursor = new InMemoryRotationCursor();
        bindings = new InMemoryTaskBindingStore();
        GatewayProperties properties = new GatewayProperties();
        properties.setDefaultTimeoutMs(5_000L);
        properties.setConnectTimeoutMs(2_000L);
        UpstreamClient client = new UpstreamClient(properties);
        AdapterRegistry registry = new AdapterRegistry(List.of(
                new OpenAiChatAdapter(client, mapper),
                new DashScopeWanAdapter(client, mapper)));
        RouteResolver routes = new RouteResolver(catalog, registry);
        AllowAllCircuitBreaker breaker = new AllowAllCircuitBreaker();
        AccountLoadBalancer balancer = new RoundRobinAccountLoadBalancer(cursor, breaker);
        ModelAdmission admission = new ModelAdmission(breaker, new AllowAllRateLimiter(), TokenUsage.NONE);
        chats = new ChatCompletionService(mapper, routes, registry, balancer, admission,
                new HeaderTimeoutPolicy(properties), bindings);
        VideoSynthesisService videos = new VideoSynthesisService(mapper, routes, registry, balancer,
                admission, new HeaderTimeoutPolicy(properties), bindings);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ChatCompletionController(chats),
                        new VideoController(videos))
                .setControllerAdvice(new GatewayExceptionHandler(mapper))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    @Test
    void plainMessageBecomesOneUserTurnUpstream() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"ok\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"temperature\":0.2,\"message\":\"你好\",\"messages\":[{\"role\":\"user\",\"content\":\"旧的\"}]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"ok\"}"));

        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
        assertEquals("qwen-max", body.get("model").asText());
        assertFalse(body.has("message"));
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertEquals("你好", body.get("messages").get(0).get("content").asText());
        assertEquals(1, body.get("messages").size());
        assertEquals(0.2, body.get("temperature").asDouble());
        assertEquals("disabled", body.get("thinking").get("type").asText());
        assertEquals("Bearer key-1", recorded.getHeader("Authorization"));
        assertEquals(path(), recorded.getPath());
        assertEquals(1, server.getRequestCount());
        assertTrue(bindings.statuses.containsValue("SUCCEEDED"));
    }

    @Test
    void explicitThinkingIsLeftUntouched() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"ok\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\",\"thinking\":{\"type\":\"enabled\"}}"))
                .andExpect(status().isOk());

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("enabled", body.get("thinking").get("type").asText());
    }

    @Test
    void blankUpstreamModelFallsBackToCallerName() throws Exception {
        ModelRow row = catalog.add("qwen-max", "openai", null, url(), FakeCatalog.account(10, 1));
        row.setUpstreamModelName("  ");
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"ok\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("qwen-max", body.get("model").asText());
    }

    @Test
    void nonRetriableUpstreamErrorIsReturnedWithoutAnotherAccount() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad-param\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"bad-param\"}"));

        assertEquals(1, server.getRequestCount());
    }

    @Test
    void rateLimitUsesTheNextAccountOnce() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"slow\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"second\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"second\"}"));

        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-2", server.takeRequest().getHeader("Authorization"));
        assertEquals(2, server.getRequestCount());
        assertEquals(1L, cursor.values.get(RedisKeys.accountCursor(1)));
    }

    @Test
    void lastUpstreamErrorIsReturnedWhenNoAccountRemains() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"error\":\"down\"}"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void streamChunksAreForwardedBeforeUpstreamFinishes() throws Exception {
        try (PausingHttpServer pausing = new PausingHttpServer()) {
            catalog.add("qwen-max", "openai", "qwen-max",
                    "http://127.0.0.1:" + pausing.port() + "/v1/chat/completions",
                    FakeCatalog.account(10, 1));
            pausing.serve("data: one\n\n", "data: two\n\n", 700);
            TimingResponse response = new TimingResponse();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContent("{\"model\":\"qwen-max\",\"stream\":true,\"message\":\"hi\"}"
                    .getBytes(StandardCharsets.UTF_8));
            long started = System.nanoTime();
            chats.handle(request, response);
            pausing.await();
            long finished = System.nanoTime();

            String text = response.getContentAsString();
            assertTrue(text.contains("data: one"));
            assertTrue(text.contains("data: two"));
            assertEquals("text/event-stream", response.getContentType());
            assertTrue(response.firstWriteNanos - started < 400_000_000L, "第一块应在上游结束前写出");
            assertTrue(finished - started > 600_000_000L, "第二块应在停顿之后才到");
        }
    }

    @Test
    void failedStreamBeforeAnyBodyRetriesTheNextAccount() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("nope"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: ok\n\n"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"stream\":true,\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data: ok")));

        assertEquals(2, server.getRequestCount());
    }

    @Test
    void messageMustBeANonBlankString() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        assertError("/v1/chat/completions", "{\"model\":\"qwen-max\"}", 400);
        assertError("/v1/chat/completions", "{\"model\":\"qwen-max\",\"message\":\"  \"}", 400);
        assertError("/v1/chat/completions", "{\"model\":\"qwen-max\",\"message\":{\"role\":\"user\",\"content\":\"你好\"}}", 400);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void routingErrorsDoNotCallUpstreamOrRevealTheKey() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.accountWithSecret(10, 1, SECRET));
        catalog.add("wan2.0", "dashscope-wan", "wan2.6-t2v", url(),
                FakeCatalog.accountWithSecret(11, 2, SECRET));
        catalog.add("disabled", "openai", "disabled", url(), FakeCatalog.account(12, 3)).setEnabled(0);
        catalog.add("foreign", "claude", "foreign", url(),
                FakeCatalog.accountWithSecret(13, 4, SECRET));
        catalog.add("empty", "openai", "empty", url());
        catalog.clearAccounts("empty");

        assertError("/v1/chat/completions", "{\"messages\":[]}", 400);
        assertError("/v1/chat/completions", "{\"model\":\"missing\",\"messages\":[]}", 404);
        assertError("/v1/chat/completions", "{\"model\":\"disabled\",\"messages\":[]}", 404);
        assertError("/v1/chat/completions", "{\"model\":\"foreign\",\"messages\":[]}", 400);
        assertError("/v1/chat/completions", "{\"model\":\"empty\",\"messages\":[]}", 503);
        assertError("/v1/chat/completions", "{\"model\":\"wan2.0\",\"messages\":[]}", 400);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void redisFailureDoesNotCallUpstream() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.accountWithSecret(10, 1, SECRET));
        RotationCursor failing = key -> {
            throw new GatewayException(503, "server_error", "轮询计数不可用");
        };
        GatewayProperties properties = new GatewayProperties();
        UpstreamClient client = new UpstreamClient(properties);
        AdapterRegistry registry = new AdapterRegistry(List.of(new OpenAiChatAdapter(client, mapper)));
        ChatCompletionService service = new ChatCompletionService(mapper, new RouteResolver(catalog, registry), registry,
                new RoundRobinAccountLoadBalancer(failing, new AllowAllCircuitBreaker()),
                new ModelAdmission(new AllowAllCircuitBreaker(), new AllowAllRateLimiter(), TokenUsage.NONE),
                new HeaderTimeoutPolicy(properties),
                new InMemoryTaskBindingStore());
        MockMvc isolated = MockMvcBuilders.standaloneSetup(new ChatCompletionController(service))
                .setControllerAdvice(new GatewayExceptionHandler(mapper))
                .build();

        String body = isolated.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(SECRET));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void invalidTimeoutIsRejected() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-Gateway-Timeout-Ms", "nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void wanCreateRewritesModelAndDoesNotWaitForVideo() throws Exception {
        catalog.add("wan2.0", "dashscope-wan", "wan2.6-t2v", url(), FakeCatalog.account(10, 1));
        catalog.add("wan3.0", "dashscope-wan", "wan3.0-video", url(), FakeCatalog.account(10, 1));
        server.enqueue(json(200, "{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}"));
        server.enqueue(json(200, "{\"output\":{\"task_id\":\"task-3\",\"task_status\":\"PENDING\"}}"));

        mockMvc.perform(post("/v1/video/synthesis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"wan2.0\",\"input\":{\"prompt\":\"猫\"},\"parameters\":{\"duration\":2}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"output\":{\"task_id\":\"task-1\",\"task_status\":\"PENDING\"}}"));
        RecordedRequest wan2 = server.takeRequest();
        String wan2Body = wan2.getBody().readUtf8();
        assertEquals("wan2.6-t2v", mapper.readTree(wan2Body).get("model").asText());
        assertEquals("enable", wan2.getHeader("X-DashScope-Async"));
        assertEquals("猫", mapper.readTree(wan2Body).get("input").get("prompt").asText());
        assertFalse(wan2Body.contains("video_url"));
        assertEquals(10, bindings.bindings.get("task-1"));
        assertEquals("PENDING", bindings.statuses.get("task-1"));

        mockMvc.perform(post("/v1/video/synthesis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"wan3.0\",\"input\":{\"prompt\":\"猫\"}}"))
                .andExpect(status().isOk());
        assertEquals("wan3.0-video", mapper.readTree(server.takeRequest().getBody().readUtf8()).get("model").asText());
    }

    @Test
    void videoParameterErrorDoesNotSwitchAccount() throws Exception {
        catalog.add("wan2.0", "dashscope-wan", "wan2.6-t2v", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(json(400, "{\"code\":\"InvalidParameter\"}"));

        mockMvc.perform(post("/v1/video/synthesis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"wan2.0\",\"input\":{\"prompt\":\"猫\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"InvalidParameter\"}"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void taskQueryUsesCreatingAccountAndTaskPath() throws Exception {
        catalog.add("wan2.0", "dashscope-wan", "wan2.6-t2v", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(30, 3));
        bindings.record("task-9", "wan2.0", 20, "PENDING");
        server.enqueue(json(200, "{\"output\":{\"task_id\":\"task-9\",\"task_status\":\"RUNNING\"}}"));

        mockMvc.perform(get("/v1/tasks/task-9").queryParam("model", "wan2.0"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"output\":{\"task_status\":\"RUNNING\"}}"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/api/v1/tasks/task-9", recorded.getPath());
        assertFalse(recorded.getPath().contains("video-synthesis"));
        assertEquals("Bearer key-2", recorded.getHeader("Authorization"));
        assertEquals("GET", recorded.getMethod());
        assertEquals("RUNNING", bindings.statuses.get("task-9"));

        mockMvc.perform(post("/v1/video/synthesis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"input\":{\"prompt\":\"猫\"}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/tasks/task-9").queryParam("model", "qwen-max"))
                .andExpect(status().isBadRequest());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void queryWithoutBindingUsesNormalRotation() throws Exception {
        catalog.add("wan2.0", "dashscope-wan", "wan2.6-t2v", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(json(200, "{\"output\":{\"task_status\":\"PENDING\"}}"));

        mockMvc.perform(get("/v1/tasks/unknown-task").queryParam("model", "wan2.0"))
                .andExpect(status().isOk());
        RecordedRequest recorded = server.takeRequest();
        assertEquals("Bearer key-1", recorded.getHeader("Authorization"));
        assertEquals("/api/v1/tasks/unknown-task", recorded.getPath());
    }

    @Test
    void disabledLoadBalancePinsTheFirstAccount() throws Exception {
        ModelRow row = catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        row.setLoadBalanceEnabled(0);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"one\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"two\"}"));

        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals(null, cursor.values.get(RedisKeys.accountCursor(row.getId())));
    }

    @Test
    void modelRateLimitRejectsWithoutSwitchingAccount() throws Exception {
        ModelRow limited = catalog.add("model-a", "openai", "model-a", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        limited.setRateLimitEnabled(1);
        limited.setRpm(1);
        catalog.add("model-b", "openai", "model-b", url(), FakeCatalog.account(30, 3));
        MockMvc api = gateway(new AllowAllCircuitBreaker(), new InMemoryModelRateLimiter(Clock.systemUTC()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"a\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"b\"}"));

        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"model-a\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());
        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"model-a\",\"message\":\"你好\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":{\"message\":\"模型请求超过限制\",\"type\":\"rate_limit_error\"}}"));
        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"model-b\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        assertEquals(2, server.getRequestCount());
        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-3", server.takeRequest().getHeader("Authorization"));
    }

    @Test
    void configuredTpmDoesNotRejectBeforeUpstream() throws Exception {
        ModelRow row = catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        row.setRateLimitEnabled(1);
        row.setRpm(10);
        row.setTpm(1);
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"ok\"}"));
        MockMvc api = gateway(new AllowAllCircuitBreaker(), new InMemoryModelRateLimiter(Clock.systemUTC()));

        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":\"ok\"}"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void vendorTpmErrorIsReturned() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        String vendor = "{\"error\":{\"message\":\"Allocated quota exceeded\",\"type\":\"limit_requests\"}}";
        server.enqueue(new MockResponse().setResponseCode(429).setBody(vendor));

        String body = mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();
        assertEquals(vendor, body);
        assertFalse(body.contains("rate_limit_error"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void secondVendorRateLimitIsReturned() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"tpm-a\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"tpm-b\"}"));

        mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"tpm-b\"}"));
        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-2", server.takeRequest().getHeader("Authorization"));
        assertEquals(2, server.getRequestCount());
        assertEquals(1L, cursor.values.get(RedisKeys.accountCursor(1)));
    }

    @Test
    void upstreamRateLimitStopsWhenTheOtherAccountIsOpen() throws Exception {
        ModelRow row = catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        InMemoryModelCircuitBreaker breaker = new InMemoryModelCircuitBreaker(
                Clock.systemUTC(), 1, 30_000L, 1, 120_000L);
        breaker.fail(row.getId(), 20, breaker.acquire(row.getId(), 20));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"tpm-a\"}"));
        MockMvc api = gateway(breaker, new AllowAllRateLimiter());

        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"error\":\"tpm-a\"}"));
        assertEquals(1, server.getRequestCount());
        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
    }

    @Test
    void openCircuitRejectsBeforeUpstream() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(), FakeCatalog.account(10, 1));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}"));
        InMemoryModelCircuitBreaker breaker = new InMemoryModelCircuitBreaker(
                Clock.systemUTC(), 1, 30_000L, 1, 120_000L);
        MockMvc api = gateway(breaker, new AllowAllRateLimiter());

        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isInternalServerError());
        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":{\"message\":\"模型暂时不可用\",\"type\":\"server_error\"}}"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void openAccountIsSkippedAndAnotherAccountIsCalled() throws Exception {
        catalog.add("qwen-max", "openai", "qwen-max", url(),
                FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"down\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"b\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"c\"}"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":\"d\"}"));
        InMemoryModelCircuitBreaker breaker = new InMemoryModelCircuitBreaker(
                Clock.systemUTC(), 1, 30_000L, 1, 120_000L);
        MockMvc api = gateway(breaker, new AllowAllRateLimiter());

        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());
        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());
        api.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"qwen-max\",\"message\":\"你好\"}"))
                .andExpect(status().isOk());

        assertEquals(4, server.getRequestCount());
        assertEquals("Bearer key-1", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-2", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-2", server.takeRequest().getHeader("Authorization"));
        assertEquals("Bearer key-2", server.takeRequest().getHeader("Authorization"));
    }

    private MockMvc gateway(com.llmgateway.routing.ModelCircuitBreaker breaker,
                            com.llmgateway.routing.RateLimiter rates) {
        GatewayProperties properties = new GatewayProperties();
        UpstreamClient client = new UpstreamClient(properties);
        AdapterRegistry registry = new AdapterRegistry(List.of(
                new OpenAiChatAdapter(client, mapper),
                new DashScopeWanAdapter(client, mapper)));
        ModelAdmission admission = new ModelAdmission(breaker, rates, TokenUsage.NONE);
        ChatCompletionService service = new ChatCompletionService(mapper, new RouteResolver(catalog, registry), registry,
                new RoundRobinAccountLoadBalancer(cursor, breaker), admission, new HeaderTimeoutPolicy(properties), bindings);
        return MockMvcBuilders.standaloneSetup(new ChatCompletionController(service))
                .setControllerAdvice(new GatewayExceptionHandler(mapper))
                .build();
    }

    private void assertError(String path, String json, int status) throws Exception {
        String body = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(SECRET));
        assertTrue(body.contains("\"type\""));
    }

    private String url() {
        return server.url(path()).toString();
    }

    private static String path() {
        return "/upstream";
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    /** 记录第一块写出的时间，用来区分流式转发和整包缓冲。 */
    private static final class TimingResponse extends MockHttpServletResponse {
        private long firstWriteNanos = -1;
        private ServletOutputStream wrapped;

        @Override
        public ServletOutputStream getOutputStream() {
            if (wrapped == null) {
                ServletOutputStream delegate = super.getOutputStream();
                wrapped = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                    }

                    @Override
                    public void write(int value) throws IOException {
                        mark();
                        delegate.write(value);
                    }

                    @Override
                    public void write(byte[] buffer, int offset, int length) throws IOException {
                        if (length > 0) {
                            mark();
                        }
                        delegate.write(buffer, offset, length);
                    }

                    private void mark() {
                        if (firstWriteNanos < 0) {
                            firstWriteNanos = System.nanoTime();
                        }
                    }
                };
            }
            return wrapped;
        }
    }
}
