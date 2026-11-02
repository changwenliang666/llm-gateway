package com.llmgateway;

import com.llmgateway.admin.TaskPayloads.DayCount;
import com.llmgateway.admin.TaskPayloads.TaskPage;
import com.llmgateway.admin.TaskPayloads.TaskSummary;
import com.llmgateway.admin.TaskQuery;
import com.llmgateway.task.ModelTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着真实库验证任务列表和按日汇总。整个方法在事务里回滚。
 * 默认不跑，避免普通测试依赖外部数据库。
 */
@SpringBootTest
@Transactional
@Rollback
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "live.db", matches = "true")
class TaskQuerySqlTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelTaskMapper tasks;

    @Autowired
    private TaskQuery query;

    @Test
    void listsByAccountAndKeepsTheCreationDayAfterStatusChange() {
        String suffix = Long.toString(System.nanoTime());
        int providerId = insertProvider("it_list_provider_" + suffix);
        int accountId = insertAccount(providerId, "it_list_account_" + suffix);
        String model = "it_list_model_" + suffix;
        String older = "it_list_old_" + suffix;
        String newer = "it_list_new_" + suffix;
        jdbc.update("""
                INSERT INTO model_tasks (task_id, model_name, provider_account_id, task_status, created_time)
                VALUES (?, ?, ?, ?, ?)
                """, older, model, accountId, "PENDING", "2026-10-01 08:00:00");
        jdbc.update("""
                INSERT INTO model_tasks (task_id, model_name, provider_account_id, task_status, created_time)
                VALUES (?, ?, ?, ?, ?)
                """, newer, model, accountId, "SUCCEEDED", "2026-10-02 09:30:00");
        tasks.updateStatus(older, "RUNNING");

        TaskPage page = query.page(model, null, accountId, "2026-10-01", "2026-10-02", 1, 20);
        assertEquals(2, page.total());
        assertEquals(newer, page.items().get(0).taskId());
        assertEquals("2026-10-02T09:30:00", page.items().get(0).createdTime());
        assertEquals("it_list_account_" + suffix, page.items().get(0).accountName());
        assertEquals(older, page.items().get(1).taskId());

        TaskPage running = query.page(model, "RUNNING", null, null, null, 1, 20);
        assertEquals(1, running.total());
        assertEquals(older, running.items().get(0).taskId());
        assertEquals("RUNNING", running.items().get(0).taskStatus());

        TaskSummary summary = query.summary("2026-10-01", "2026-10-02");
        assertEquals(2, summary.byDay().size());
        DayCount first = summary.byDay().get(0);
        DayCount second = summary.byDay().get(1);
        assertEquals("2026-10-01", first.date());
        assertEquals(1, first.count());
        assertEquals("2026-10-02", second.date());
        assertEquals(1, second.count());
        assertTrue(summary.byStatus().stream().anyMatch(item -> item.status().equals("RUNNING") && item.count() == 1));
        assertEquals("2026-10-01 08:00:00", jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_time, '%Y-%m-%d %H:%i:%s') FROM model_tasks WHERE task_id = ?",
                String.class, older));
    }

    private int insertProvider(String name) {
        jdbc.update("INSERT INTO providers (provider_name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT id FROM providers WHERE provider_name = ?", Integer.class, name);
    }

    private int insertAccount(int providerId, String name) {
        jdbc.update("INSERT INTO provider_accounts (provider_id, account_name, enabled) VALUES (?, ?, 1)",
                providerId, name);
        return jdbc.queryForObject(
                "SELECT id FROM provider_accounts WHERE provider_id = ? AND account_name = ?",
                Integer.class, providerId, name);
    }
}
