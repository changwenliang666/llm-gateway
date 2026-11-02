package com.llmgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.admin.AdminAuthFilter;
import com.llmgateway.admin.AdminController;
import com.llmgateway.admin.AdminPageController;
import com.llmgateway.admin.AdminPayloads.ProviderBody;
import com.llmgateway.admin.AdminService;
import com.llmgateway.admin.TaskQuery;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.GatewayExceptionHandler;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.MemoryAdminStore;
import com.llmgateway.task.ModelTaskMapper;
import com.llmgateway.task.TaskDayCount;
import com.llmgateway.task.TaskListRow;
import com.llmgateway.task.TaskStatusCount;
import com.llmgateway.upstream.AdapterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskAdminTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MemoryAdminStore store = new MemoryAdminStore();
        AdminService admin = new AdminService(store, new FakeCatalog(), new AdapterRegistry(List.of()));
        admin.createProvider(new ProviderBody("阿里"));
        GatewayProperties properties = new GatewayProperties();
        properties.setAdminToken("secret");
        QuietTasks tasks = new QuietTasks();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminController(admin, new TaskQuery(tasks)),
                        new AdminPageController())
                .addFilters(new AdminAuthFilter(properties, new ObjectMapper()))
                .setControllerAdvice(new GatewayExceptionHandler(new ObjectMapper()))
                .build();
    }

    @Test
    void taskListRequiresTokenAndRejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/admin/api/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/tasks").param("size", "101").header(AdminAuthFilter.HEADER, "secret"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/api/tasks").header(AdminAuthFilter.HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void summaryIsNotCapturedAsATaskId() throws Exception {
        mockMvc.perform(get("/admin/api/tasks/summary").header(AdminAuthFilter.HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-19"))
                .andExpect(jsonPath("$.to").value("2026-11-01"))
                .andExpect(jsonPath("$.byDay.length()").value(14))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void adminPathsForwardOnlyTheConsole() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(forwardedUrl("/admin/index.html"));
        mockMvc.perform(get("/admin/")).andExpect(forwardedUrl("/admin/index.html"));
        mockMvc.perform(get("/admin/api/providers").header(AdminAuthFilter.HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(null));
    }

    /** 汇总用固定的数据库日期，列表保持空，避免测试碰到真实库。 */
    private static final class QuietTasks implements ModelTaskMapper {
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
