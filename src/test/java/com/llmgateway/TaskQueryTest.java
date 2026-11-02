package com.llmgateway;

import com.llmgateway.admin.TaskPayloads.DayCount;
import com.llmgateway.admin.TaskPayloads.TaskPage;
import com.llmgateway.admin.TaskPayloads.TaskSummary;
import com.llmgateway.admin.TaskQuery;
import com.llmgateway.error.GatewayException;
import com.llmgateway.task.ModelTaskMapper;
import com.llmgateway.task.TaskDayCount;
import com.llmgateway.task.TaskListRow;
import com.llmgateway.task.TaskStatusCount;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQueryTest {

    private final FakeTasks tasks = new FakeTasks();
    private final TaskQuery query = new TaskQuery(tasks);

    @Test
    void listAndCountShareTheSameFilter() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapper/ModelTaskMapper.xml"));
        assertTrue(xml.contains("<sql id=\"taskFilter\">"));
        assertEquals(2, countOf(xml, "<include refid=\"taskFilter\"/>"));
        String script = Files.readString(Path.of("sql/model_task_list.sql"));
        assertTrue(script.contains("idx_model_tasks_created_status"));
        assertFalse(script.toLowerCase().contains("drop table"));
        for (String table : List.of("providers", "provider_accounts", "account_keys", "models", "model_accounts")) {
            assertFalse(script.contains("ALTER TABLE `" + table + "`"));
        }
        String schema = Files.readString(Path.of("sql/schema.sql"));
        assertTrue(schema.contains("idx_model_tasks_created_status"));
    }

    @Test
    void rejectsBadPageAndReversedDates() {
        GatewayException size = assertThrows(GatewayException.class,
                () -> query.page(null, null, null, null, null, 1, 101));
        assertEquals(400, size.status());
        assertEquals(0, tasks.pageCalls);

        GatewayException page = assertThrows(GatewayException.class,
                () -> query.page(null, null, null, null, null, 0, 20));
        assertEquals(400, page.status());

        GatewayException dates = assertThrows(GatewayException.class,
                () -> query.page(null, null, null, "2026-11-02", "2026-11-01", 1, 20));
        assertEquals(400, dates.status());

        GatewayException malformed = assertThrows(GatewayException.class,
                () -> query.page(null, null, null, "11/01/2026", null, 1, 20));
        assertEquals(400, malformed.status());
        assertEquals("日期格式应为 yyyy-MM-dd", malformed.getMessage());
    }

    @Test
    void emptyPageUsesDefaults() {
        TaskPage result = query.page("  ", null, null, null, null, null, null);
        assertEquals(0, result.total());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertTrue(result.items().isEmpty());
        assertEquals(0, tasks.lastOffset);
        assertEquals(20, tasks.lastLimit);
        assertEquals(null, tasks.lastModel);
    }

    @Test
    void secondPageOffsetsFromTheFirst() {
        tasks.total = 25;
        TaskPage result = query.page("wan", "SUCCEEDED", 7, "2026-10-01", "2026-10-02", 2, 10);
        assertEquals(25, result.total());
        assertEquals(2, result.page());
        assertEquals(10, result.size());
        assertEquals(10, tasks.lastOffset);
        assertEquals("wan", tasks.lastModel);
        assertEquals("SUCCEEDED", tasks.lastStatus);
        assertEquals(7, tasks.lastAccountId);
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), tasks.lastFrom);
        assertEquals(LocalDateTime.of(2026, 10, 3, 0, 0), tasks.lastToExclusive);
    }

    @Test
    void summaryFillsMissingDaysAndRejectsLongRange() {
        tasks.today = LocalDate.of(2026, 11, 1);
        TaskDayCount hit = new TaskDayCount();
        hit.setDay("2026-11-01");
        hit.setTotal(3);
        tasks.days.add(hit);
        TaskStatusCount status = new TaskStatusCount();
        status.setStatus("SUCCEEDED");
        status.setTotal(3);
        tasks.statuses.add(status);
        TaskStatusCount empty = new TaskStatusCount();
        empty.setStatus("FAILED");
        empty.setTotal(0);
        tasks.statuses.add(empty);

        TaskSummary summary = query.summary(null, null);
        assertEquals("2026-10-19", summary.from());
        assertEquals("2026-11-01", summary.to());
        assertEquals(14, summary.byDay().size());
        assertEquals("2026-10-19", summary.byDay().get(0).date());
        assertEquals(0, summary.byDay().get(0).count());
        DayCount last = summary.byDay().get(13);
        assertEquals("2026-11-01", last.date());
        assertEquals(3, last.count());
        assertEquals(1, summary.byStatus().size());
        assertEquals("SUCCEEDED", summary.byStatus().get(0).status());

        TaskSummary onlyTo = query.summary(null, "2026-10-20");
        assertEquals("2026-10-07", onlyTo.from());
        assertEquals("2026-10-20", onlyTo.to());

        TaskSummary onlyFrom = query.summary("2026-10-20", null);
        assertEquals("2026-10-20", onlyFrom.from());
        assertEquals("2026-11-01", onlyFrom.to());

        GatewayException span = assertThrows(GatewayException.class,
                () -> query.summary("2026-01-01", "2026-04-02"));
        assertEquals(400, span.status());
        assertEquals("查询跨度不能超过 90 天", span.getMessage());

        GatewayException reversed = assertThrows(GatewayException.class,
                () -> query.summary("2026-11-02", "2026-11-01"));
        assertEquals(400, reversed.status());
    }

    @Test
    void emptySummaryStillReturnsEveryDay() {
        tasks.today = LocalDate.of(2026, 11, 1);
        TaskSummary summary = query.summary(" ", " ");
        assertEquals(14, summary.byDay().size());
        assertTrue(summary.byStatus().isEmpty());
        assertTrue(summary.byDay().stream().allMatch(day -> day.count() == 0));
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + token.length();
        }
    }

    private static final class FakeTasks implements ModelTaskMapper {
        private LocalDate today = LocalDate.of(2026, 11, 1);
        private long total;
        private int pageCalls;
        private int lastLimit;
        private long lastOffset;
        private String lastModel;
        private String lastStatus;
        private Integer lastAccountId;
        private LocalDateTime lastFrom;
        private LocalDateTime lastToExclusive;
        private final List<TaskDayCount> days = new ArrayList<>();
        private final List<TaskStatusCount> statuses = new ArrayList<>();

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
            pageCalls++;
            lastModel = modelName;
            lastStatus = taskStatus;
            lastAccountId = accountId;
            lastFrom = from;
            lastToExclusive = toExclusive;
            lastLimit = limit;
            lastOffset = offset;
            return List.of();
        }

        @Override
        public long count(String modelName, String taskStatus, Integer accountId,
                          LocalDateTime from, LocalDateTime toExclusive) {
            return total;
        }

        @Override
        public List<TaskStatusCount> countByStatus(LocalDateTime from, LocalDateTime toExclusive) {
            return statuses;
        }

        @Override
        public List<TaskDayCount> countByDay(LocalDateTime from, LocalDateTime toExclusive) {
            return days;
        }

        @Override
        public LocalDate currentDate() {
            return today;
        }
    }
}
