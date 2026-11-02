package com.llmgateway.upstream.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.upstream.AdapterOperation;
import com.llmgateway.upstream.InvokeCommand;
import com.llmgateway.upstream.InvokeResult;
import com.llmgateway.upstream.JsonBodies;
import com.llmgateway.upstream.ProtocolAdapter;
import com.llmgateway.upstream.ResponseWriter;
import com.llmgateway.upstream.UpstreamClient;
import com.llmgateway.upstream.UpstreamReply;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * OpenAI Chat Completions 协议。通义千问、DeepSeek 走这一份代码。
 * Google、字节若提供兼容该协议的地址，也只在模型表登记 {@code adapter_name=openai} 和完整 URL，
 * 不按供应商再分叉。请求 URL 就是模型表里的 {@code base_url}，不再拼接 {@code /chat/completions}。
 *
 * <p>发给上游之前只改两处：JSON 的 {@code model} 换成上游模型名，请求头换成选出的
 * {@code Authorization: Bearer}。调用方自己的 Authorization 不会被转发，Key 也不会出现在响应里。
 * {@code messages}、{@code thinking}、{@code max_tokens} 等字段由编排层准备好，这里原样序列化。
 */
@Component
public class OpenAiChatAdapter implements ProtocolAdapter {

    public static final String NAME = "openai";

    private final UpstreamClient client;
    private final ObjectMapper mapper;

    public OpenAiChatAdapter(UpstreamClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String label() {
        return "OpenAI 兼容文本";
    }

    @Override
    public boolean supports(AdapterOperation operation) {
        return operation == AdapterOperation.CHAT_COMPLETION;
    }

    /**
     * 非流式把整段正文留在内存，交给编排层决定是返回还是换账号。
     * 流式先看状态码：401、429、5xx 仍整段读入并标记可重试，因为一个字节都还没给调用方。
     * 2xx 的流从这里开始直接写 response，返回 {@code committed=true}，编排层不能再重试。
     */
    @Override
    public InvokeResult invoke(InvokeCommand command, HttpServletResponse response) throws IOException {
        byte[] payload = JsonBodies.replaceModel(
                mapper, command.body(), command.selection().route().upstreamModelName());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + command.selection().credential().apiKey());
        headers.put("Content-Type", "application/json");
        URI uri = URI.create(command.selection().route().baseUrl());
        // 流式拷贝时 HttpClient 的 timeout 只约束到响应头。正文拖太久要靠这个截止时间自己断开。
        long deadline = System.nanoTime() + command.timeout().toNanos();
        if (!command.stream()) {
            try (UpstreamReply reply = client.send(uri, "POST", headers, payload, command.timeout(), false)) {
                return InvokeResult.buffered(
                        reply.status(),
                        "application/json",
                        reply.body(),
                        JsonBodies.retryableStatus(reply.status()));
            }
        }
        try (UpstreamReply reply = client.send(uri, "POST", headers, payload, command.timeout(), true)) {
            if (JsonBodies.retryableStatus(reply.status())) {
                byte[] errorBody = readAll(reply.stream());
                return InvokeResult.buffered(reply.status(), "application/json", errorBody, true);
            }
            response.setStatus(reply.status());
            // 不用 setCharacterEncoding，否则容器会把 charset 拼进 Content-Type，调用方就看不到纯 text/event-stream。
            response.setContentType("text/event-stream");
            ResponseWriter.copyStream(reply.stream(), response.getOutputStream(), deadline);
            response.flushBuffer();
            return InvokeResult.streamed(reply.status());
        }
    }

    @Override
    public OptionalInt reportedTotalTokens(InvokeResult result) {
        if (result.body() == null || result.body().length == 0) {
            return OptionalInt.empty();
        }
        try {
            JsonNode tokens = mapper.readTree(result.body()).path("usage").path("total_tokens");
            if (!tokens.isNumber()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(tokens.asInt());
        } catch (IOException ex) {
            return OptionalInt.empty();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        return output.toByteArray();
    }
}
