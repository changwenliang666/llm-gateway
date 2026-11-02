package com.llmgateway.upstream.wan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.upstream.AdapterOperation;
import com.llmgateway.upstream.InvokeCommand;
import com.llmgateway.upstream.InvokeResult;
import com.llmgateway.upstream.JsonBodies;
import com.llmgateway.upstream.ProtocolAdapter;
import com.llmgateway.upstream.UpstreamClient;
import com.llmgateway.upstream.UpstreamReply;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百炼万相异步视频协议。{@code wan2.6-t2v} 和 {@code wan3.0-video} 共用这一份适配器，
 * 模型名的差别只在 {@code models.upstream_model_name}，代码里没有按模型名分支。
 *
 * <p>创建必须带 {@code X-DashScope-Async: enable}，否则百炼把这次调用当成同步接口并拒绝。
 * 创建响应里只有任务号和 PENDING 一类状态，没有成片地址。本适配器不循环查询到完成，
 * 调用方拿任务号再打 {@code GET /v1/tasks/{taskId}}。
 *
 * <p>查询地址不用模型表里的合成路径。它只借用 {@code base_url} 的协议和主机，
 * 换成 {@code /api/v1/tasks/{taskId}}，见 {@link TaskUris}。
 */
@Component
public class DashScopeWanAdapter implements ProtocolAdapter {

    public static final String NAME = "dashscope-wan";
    static final String ASYNC_HEADER = "X-DashScope-Async";

    private final UpstreamClient client;
    private final ObjectMapper mapper;

    public DashScopeWanAdapter(UpstreamClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "万相视频";
    }

    @Override
    public boolean supports(AdapterOperation operation) {
        return operation == AdapterOperation.VIDEO_CREATE || operation == AdapterOperation.VIDEO_QUERY;
    }

    @Override
    public InvokeResult invoke(InvokeCommand command, HttpServletResponse response) throws IOException {
        if (command.operation() == AdapterOperation.VIDEO_QUERY) {
            return query(command);
        }
        return create(command);
    }

    private InvokeResult create(InvokeCommand command) throws IOException {
        byte[] payload = JsonBodies.replaceModel(
                mapper, command.body(), command.selection().route().upstreamModelName());
        Map<String, String> headers = bearer(command);
        headers.put("Content-Type", "application/json");
        headers.put(ASYNC_HEADER, "enable");
        URI uri = URI.create(command.selection().route().baseUrl());
        try (UpstreamReply reply = client.send(uri, "POST", headers, payload, command.timeout(), false)) {
            byte[] body = reply.body() == null ? new byte[0] : reply.body();
            return InvokeResult.buffered(reply.status(), "application/json", body,
                    JsonBodies.retryableStatus(reply.status()));
        }
    }

    private InvokeResult query(InvokeCommand command) throws IOException {
        Map<String, String> headers = bearer(command);
        URI uri = TaskUris.query(command.selection().route().baseUrl(), command.taskId());
        try (UpstreamReply reply = client.send(uri, "GET", headers, null, command.timeout(), false)) {
            byte[] body = reply.body() == null ? new byte[0] : reply.body();
            return InvokeResult.buffered(reply.status(), "application/json", body,
                    JsonBodies.retryableStatus(reply.status()));
        }
    }

    private Map<String, String> bearer(InvokeCommand command) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + command.selection().credential().apiKey());
        return headers;
    }

}
