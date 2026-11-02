package com.llmgateway.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 把网关异常写成 JSON。响应一旦已经开始往外写（例如流式中途失败），就不再改状态码，
 * 否则调用方会看到前后两段互相覆盖的正文。
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(GatewayException.class)
    public void handleGateway(GatewayException ex, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            log.warn("响应已写出，不再覆盖网关错误: {}", ex.getMessage());
            return;
        }
        write(response, ex.status(), ErrorResponse.of(ex.getMessage(), ex.type()));
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("未处理的网关异常", ex);
        if (response.isCommitted()) {
            return;
        }
        write(response, 500, ErrorResponse.of("网关内部错误", ErrorTypes.SERVER_ERROR));
    }

    private void write(HttpServletResponse response, int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
