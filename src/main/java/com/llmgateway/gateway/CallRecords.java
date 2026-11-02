package com.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.routing.Selection;
import com.llmgateway.upstream.InvokeResult;
import com.llmgateway.upstream.wan.TaskBindingStore;

import java.io.IOException;
import java.util.UUID;

/**
 * 把一次已经返回的模型调用收成 {@code model_tasks} 的一行。编排层调用它，适配器不直接写库。
 *
 * <p>主键怎么定：
 * <ul>
 *   <li>视频创建的正文里有 {@code output.task_id} 时，用它。查询接口后来就靠这个号找回账号。</li>
 *   <li>文本对话没有这个字段，用网关生成的 UUID。同一句话不会在表里合并成一行。</li>
 * </ul>
 * 状态优先用上游的 {@code output.task_status}，例如 PENDING、RUNNING。
 * 文本成功没有这个字段，2xx 记 SUCCEEDED，其余记 FAILED。
 * 同一个任务号再次写入时，SQL 保留第一次的账号和模型名，只改状态。
 */
final class CallRecords {

    private CallRecords() {
    }

    /**
     * 成功拿到 HTTP 响应后记账。视频正文里的 task_id 优先，这样查询路径和表主键是同一个字符串。
     * 解析不出任务号时生成 UUID，文本对话每次调用都是新的一行。
     */
    static void record(TaskBindingStore store, ObjectMapper mapper, Selection selection, InvokeResult result) {
        String upstreamId = outputText(mapper, result.body(), "task_id");
        String taskId = upstreamId == null ? UUID.randomUUID().toString() : upstreamId;
        store.record(taskId, selection.route().modelName(), selection.account().accountId(),
                statusOf(mapper, result));
    }

    /** 还没拿到 HTTP 状态就失败，例如超时或连接失败。没有上游正文，状态固定记 FAILED。 */
    static void recordFailure(TaskBindingStore store, Selection selection) {
        store.record(UUID.randomUUID().toString(), selection.route().modelName(),
                selection.account().accountId(), "FAILED");
    }

    /**
     * 查询不是新任务，只把已有行的状态改成这次看到的结果。
     * 查询本身失败、正文里又没有 task_status 时不改旧状态，避免一次参数错误把仍在跑的任务记成失败。
     */
    static void refreshStatus(TaskBindingStore store, ObjectMapper mapper, String taskId, InvokeResult result) {
        String status = outputText(mapper, result.body(), "task_status");
        if (status == null && result.status() >= 200 && result.status() < 300) {
            status = "SUCCEEDED";
        }
        if (status != null) {
            store.updateStatus(taskId, status);
        }
    }

    private static String statusOf(ObjectMapper mapper, InvokeResult result) {
        String status = outputText(mapper, result.body(), "task_status");
        if (status != null) {
            return status;
        }
        return result.status() >= 200 && result.status() < 300 ? "SUCCEEDED" : "FAILED";
    }

    private static String outputText(ObjectMapper mapper, byte[] body, String field) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(body).path("output").path(field);
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        } catch (IOException ex) {
            return null;
        }
        return null;
    }
}
