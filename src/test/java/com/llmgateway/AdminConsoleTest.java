package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.admin.AdminAuthFilter;
import com.llmgateway.admin.AdminController;
import com.llmgateway.admin.AdminPayloads.AccountBody;
import com.llmgateway.admin.AdminPayloads.KeyBody;
import com.llmgateway.admin.AdminPayloads.KeyView;
import com.llmgateway.admin.AdminPayloads.ModelBody;
import com.llmgateway.admin.AdminPayloads.ProviderBody;
import com.llmgateway.admin.AdminPayloads.RateLimitBody;
import com.llmgateway.admin.AdminService;
import com.llmgateway.admin.ApiKeys;
import com.llmgateway.admin.TaskQuery;
import com.llmgateway.task.ModelTaskMapper;
import com.llmgateway.task.TaskDayCount;
import com.llmgateway.task.TaskListRow;
import com.llmgateway.task.TaskStatusCount;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.GatewayException;
import com.llmgateway.error.GatewayExceptionHandler;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.MemoryAdminStore;
import com.llmgateway.upstream.AdapterOperation;
import com.llmgateway.upstream.AdapterRegistry;
import com.llmgateway.upstream.InvokeCommand;
import com.llmgateway.upstream.InvokeResult;
import com.llmgateway.upstream.ProtocolAdapter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminConsoleTest {

    private MemoryAdminStore store;
    private FakeCatalog catalog;
    private AdminService admin;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        store = new MemoryAdminStore();
        catalog = new FakeCatalog();
        properties = new GatewayProperties();
        properties.setAdminToken("secret");
        admin = new AdminService(store, catalog, new AdapterRegistry(List.of(new NamedAdapter("openai"))));
        admin.createProvider(new ProviderBody("阿里"));
    }

    @Test
    void keysAreMaskedAndBlankUpdateKeepsTheSecret() {
        var account = admin.createAccount(new AccountBody(1, "主账号", 1));
        KeyView created = admin.createKey(account.id(), new KeyBody("线上", "sk-live-secret-1234", 1));
        assertEquals("****1234", created.apiKeyMask());
        assertFalse(created.toString().contains("sk-live-secret"));
        assertEquals("sk-live-secret-1234", store.key(created.id()).getApiKey());

        KeyView renamed = admin.updateKey(created.id(), new KeyBody("备用", "****1234", 1));
        assertEquals("备用", renamed.keyName());
        assertEquals("sk-live-secret-1234", store.key(created.id()).getApiKey());
        assertEquals("****", ApiKeys.mask("sk"));
    }

    @Test
    void loadBalanceRequiresTwoSchedulableAccounts() {
        var model = admin.createModel(modelBody(false, List.of()));
        catalog.bind(model.id(), FakeCatalog.account(10, 1));

        GatewayException rejected = assertThrows(GatewayException.class,
                () -> admin.updateLoadBalance(model.id(), true));
        assertEquals(400, rejected.status());
        assertEquals(0, store.model(model.id()).getLoadBalanceEnabled());

        catalog.bind(model.id(), FakeCatalog.account(10, 1), FakeCatalog.account(20, 2));
        admin.updateLoadBalance(model.id(), true);
        assertEquals(1, store.model(model.id()).getLoadBalanceEnabled());

        GatewayException limit = assertThrows(GatewayException.class,
                () -> admin.updateRateLimit(model.id(), new RateLimitBody(true, null, 1000)));
        assertEquals("开启限流时必须填写正数 RPM", limit.getMessage());
        assertEquals(0, store.model(model.id()).getRateLimitEnabled());

        admin.updateRateLimit(model.id(), new RateLimitBody(true, 60, 1000));
        assertEquals(1, store.model(model.id()).getRateLimitEnabled());
        assertEquals(60, store.model(model.id()).getRpm());
        assertNull(store.model(model.id()).getTpm());
    }

    @Test
    void unknownAdapterAndForeignAccountAreRejected() {
        GatewayException adapter = assertThrows(GatewayException.class,
                () -> admin.createModel(new ModelBody(1, "bad", "google-native", null, "http://upstream",
                        1, false, false, null, null, List.of())));
        assertEquals("不支持的协议适配器", adapter.getMessage());

        var other = admin.createProvider(new ProviderBody("字节"));
        var foreign = admin.createAccount(new AccountBody(other.id(), "方舟", 1));
        GatewayException link = assertThrows(GatewayException.class,
                () -> admin.createModel(modelBody(false, List.of(foreign.id()))));
        assertEquals("账号不属于该供应商", link.getMessage());
    }

    @Test
    void adminTokenIsRequiredOnlyForAdminApi() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter(properties, new ObjectMapper());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(admin, new TaskQuery(new UnusedTasks())))
                .addFilters(filter)
                .setControllerAdvice(new GatewayExceptionHandler(new ObjectMapper()))
                .build();
        mockMvc.perform(get("/admin/api/providers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/providers").header(AdminAuthFilter.HEADER, "secret"))
                .andExpect(status().isOk());

        properties.setAdminToken("");
        AtomicBoolean passed = new AtomicBoolean();
        MockHttpServletRequest caller = new MockHttpServletRequest("POST", "/v1/chat/completions");
        filter.doFilter(caller, new MockHttpServletResponse(), (request, response) -> passed.set(true));
        assertTrue(passed.get());

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/admin/api/models"), blocked, (request, response) -> {
        });
        assertEquals(401, blocked.getStatus());
    }

    @Test
    void modelListReturnsConsumedTokens() {
        var created = admin.createModel(modelBody(false, List.of()));
        store.model(created.id()).setConsumedTokens(1280);
        var listed = admin.models().get(0);
        assertEquals(1280L, listed.consumedTokens());
    }

    @Test
    void adminPageCallsLiveApis() throws IOException {
        String script = Files.readString(Path.of("console/src/api/http.js"));
        assertTrue(script.contains("/admin/api/providers"));
        assertTrue(script.contains("/admin/api/accounts"));
        assertTrue(script.contains("/admin/api/models"));
        assertTrue(script.contains("/invocation"));
        assertTrue(script.contains("/load-balance"));
        assertTrue(script.contains("/rate-limit"));
        assertTrue(script.contains("/admin/api/tasks"));
        assertTrue(script.contains("X-Admin-Token"));
        assertFalse(script.contains("mockData"));
        String usage = Files.readString(Path.of("console/src/views/UsageView.vue"));
        String overview = Files.readString(Path.of("console/src/views/OverviewView.vue"));
        String accounts = Files.readString(Path.of("console/src/views/AccountsView.vue"));
        assertTrue(usage.contains("consumedTokens"));
        assertTrue(overview.contains("paths.taskSummary"));
        assertTrue(accounts.contains("apiKeyMask"));
        assertTrue(accounts.contains("paths.keys"));
        assertFalse(usage.contains("mockData"));
        assertFalse(overview.contains("mockData"));
        assertFalse(accounts.contains("row.apiKey"));
    }

    private ModelBody modelBody(boolean loadBalance, List<Integer> accountIds) {
        return new ModelBody(1, "qwen-max", "openai", "qwen-max", "http://upstream",
                1, loadBalance, false, null, null, accountIds);
    }

    private static final class NamedAdapter implements ProtocolAdapter {
        private final String name;

        private NamedAdapter(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(AdapterOperation operation) {
            return operation == AdapterOperation.CHAT_COMPLETION;
        }

        @Override
        public InvokeResult invoke(InvokeCommand command, HttpServletResponse response) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedTasks implements ModelTaskMapper {
        @Override
        public void insert(String taskId, String modelName, int accountId, String taskStatus) {
        }

        @Override
        public void updateStatus(String taskId, String taskStatus) {
        }

        @Override
        public Integer findAccountId(String taskId) {
            return null;
        }

        @Override
        public List<TaskListRow> page(String modelName, String taskStatus, Integer accountId,
                                      LocalDateTime from, LocalDateTime toExclusive, int limit, long offset) {
            return List.of();
        }

        @Override
        public long count(String modelName, String taskStatus, Integer accountId,
                          LocalDateTime from, LocalDateTime toExclusive) {
            return 0;
        }

        @Override
        public List<TaskStatusCount> countByStatus(LocalDateTime from, LocalDateTime toExclusive) {
            return List.of();
        }

        @Override
        public List<TaskDayCount> countByDay(LocalDateTime from, LocalDateTime toExclusive) {
            return List.of();
        }

        @Override
        public LocalDate currentDate() {
            return LocalDate.of(2026, 11, 1);
        }
    }
}
