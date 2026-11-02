package com.llmgateway.admin;

import com.llmgateway.admin.TaskPayloads.DayCount;
import com.llmgateway.admin.TaskPayloads.StatusCount;
import com.llmgateway.admin.TaskPayloads.TaskItem;
import com.llmgateway.admin.TaskPayloads.TaskPage;
import com.llmgateway.admin.TaskPayloads.TaskSummary;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.task.ModelTaskMapper;
import com.llmgateway.task.TaskDayCount;
import com.llmgateway.task.TaskListRow;
import com.llmgateway.task.TaskStatusCount;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端只读任务。日期窗口用数据库的今天，和 model_tasks.created_time 同一个时钟。
 * 按日结果在这里补零，图的横轴才不会缺天。
 */
@Service
public class TaskQuery {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_DAYS = 14;
    static final int MAX_SUMMARY_DAYS = 90;

    private final ModelTaskMapper tasks;

    public TaskQuery(ModelTaskMapper tasks) {
        this.tasks = tasks;
    }

    public TaskPage page(String modelName, String taskStatus, Integer accountId,
                         String fromText, String toText, Integer page, Integer size) {
        int pageNo = page == null ? 1 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNo < 1) {
            throw bad("页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw bad("每页条数必须在 1 到 100 之间");
        }
        LocalDate from = parseDate(fromText);
        LocalDate to = parseDate(toText);
        if (from != null && to != null && from.isAfter(to)) {
            throw bad("开始日期不能晚于结束日期");
        }
        String model = blankToNull(modelName);
        String status = blankToNull(taskStatus);
        LocalDateTime start = from == null ? null : from.atStartOfDay();
        LocalDateTime end = to == null ? null : to.plusDays(1).atStartOfDay();
        long total = tasks.count(model, status, accountId, start, end);
        List<TaskItem> items = tasks.page(model, status, accountId, start, end, pageSize,
                        (long) (pageNo - 1) * pageSize)
                .stream()
                .map(TaskQuery::item)
                .toList();
        return new TaskPage(total, pageNo, pageSize, items);
    }

    public TaskSummary summary(String fromText, String toText) {
        LocalDate from = parseDate(fromText);
        LocalDate to = parseDate(toText);
        if (from == null || to == null) {
            LocalDate today = tasks.currentDate();
            if (today == null) {
                throw new GatewayException(500, ErrorTypes.SERVER_ERROR, "数据库没有返回当前日期");
            }
            if (to == null) {
                to = today;
            }
            if (from == null) {
                from = to.minusDays(DEFAULT_DAYS - 1L);
            }
        }
        if (from.isAfter(to)) {
            throw bad("开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_SUMMARY_DAYS) {
            throw bad("查询跨度不能超过 90 天");
        }
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<StatusCount> byStatus = new ArrayList<>();
        for (TaskStatusCount row : tasks.countByStatus(start, end)) {
            if (row.getTotal() > 0) {
                byStatus.add(new StatusCount(row.getStatus(), row.getTotal()));
            }
        }
        Map<String, Long> found = new HashMap<>();
        for (TaskDayCount row : tasks.countByDay(start, end)) {
            if (row.getDay() != null) {
                found.put(row.getDay(), row.getTotal());
            }
        }
        List<DayCount> byDay = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            byDay.add(new DayCount(day.toString(), found.getOrDefault(day.toString(), 0L)));
        }
        return new TaskSummary(from.toString(), to.toString(), List.copyOf(byStatus), List.copyOf(byDay));
    }

    private static TaskItem item(TaskListRow row) {
        return new TaskItem(row.getTaskId(), row.getModelName(), row.getAccountId(), row.getAccountName(),
                row.getTaskStatus(), row.getCreatedTime());
    }

    private static LocalDate parseDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw bad("日期格式应为 yyyy-MM-dd");
        }
    }

    private static String blankToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private static GatewayException bad(String message) {
        return new GatewayException(400, ErrorTypes.INVALID_REQUEST, message);
    }
}
