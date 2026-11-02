package com.llmgateway.upstream;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * 把上游结果写给调用方。
 * {@link #writeBuffered} 用于整段已经读完的 JSON：非流式对话、视频创建、视频查询，以及可重试的错误。
 * {@link #copyStream} 用于流式对话，每读到一块就 flush，调用方不用等上游把整段 SSE 生成完。
 */
public final class ResponseWriter {

    private ResponseWriter() {
    }

    public static void writeBuffered(HttpServletResponse response, InvokeResult result) throws IOException {
        if (result.committed()) {
            return;
        }
        response.setStatus(result.status());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(result.contentType() == null ? "application/json" : result.contentType());
        byte[] body = result.body() == null ? new byte[0] : result.body();
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * 按 1KB 往外抄。deadlineNanos 是这次请求开始时算好的截止时间，
     * 上游响应头已经返回、正文却一直不结束时，在这里变成 504，避免连接一直占着。
     */
    public static void copyStream(InputStream input, ServletOutputStream output, long deadlineNanos) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (System.nanoTime() > deadlineNanos) {
                throw new com.llmgateway.error.GatewayException(
                        504, com.llmgateway.error.ErrorTypes.SERVER_ERROR, "上游调用超时");
            }
            output.write(buffer, 0, read);
            output.flush();
        }
    }
}
