package com.llmgateway.web;

import com.llmgateway.gateway.VideoSynthesisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 视频的 HTTP 入口。创建是 {@code POST /v1/video/synthesis}，查询是 {@code GET /v1/tasks/{taskId}?model=}。
 * 查询必须再带模型名：任务号只是百炼返回的一串字符，看不出该用哪张表里的地址和协议。
 * 任务号是否包含斜线，由 {@code TaskUris} 检查，控制器不解析它。
 */
@RestController
public class VideoController {

    private final VideoSynthesisService service;

    public VideoController(VideoSynthesisService service) {
        this.service = service;
    }

    @PostMapping("/v1/video/synthesis")
    public void create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        service.create(request, response);
    }

    @GetMapping("/v1/tasks/{taskId}")
    public void query(@PathVariable("taskId") String taskId,
                      @RequestParam(name = "model", required = false) String model,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        service.query(taskId, model, request, response);
    }
}
