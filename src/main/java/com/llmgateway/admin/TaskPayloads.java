package com.llmgateway.admin;

import java.util.List;

/** 任务列表和汇总的响应。里面没有请求或响应正文。 */
public final class TaskPayloads {

    private TaskPayloads() {
    }

    public record TaskItem(String taskId, String modelName, int accountId, String accountName,
                           String taskStatus, String createdTime) {
    }

    public record TaskPage(long total, int page, int size, List<TaskItem> items) {
    }

    public record StatusCount(String status, long count) {
    }

    public record DayCount(String date, long count) {
    }

    public record TaskSummary(String from, String to, List<StatusCount> byStatus, List<DayCount> byDay) {
    }
}
