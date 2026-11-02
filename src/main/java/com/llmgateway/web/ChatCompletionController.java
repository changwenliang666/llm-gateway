package com.llmgateway.web;

import com.llmgateway.gateway.ChatCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 对话的 HTTP 入口，路径固定为 {@code POST /v1/chat/completions}。
 * 不读模型名，不选账号。返回 {@code void} 是为了让服务直接写响应：
 * 非流式写 JSON，流式按块写 {@code text/event-stream}。若改成返回对象，流式会被缓冲到结束才吐出。
 */
@RestController
public class ChatCompletionController {

    private final ChatCompletionService service;

    public ChatCompletionController(ChatCompletionService service) {
        this.service = service;
    }

    @PostMapping("/v1/chat/completions")
    public void chat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        service.handle(request, response);
    }
}
