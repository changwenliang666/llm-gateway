package com.llmgateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.ErrorResponse;
import com.llmgateway.error.ErrorTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 只挡住 /admin/api。调用方的 /v1 和静态管理页不经过这里。 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Token";

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/admin/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = properties.getAdminToken();
        String given = request.getHeader(HEADER);
        if (!StringUtils.hasText(expected) || given == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String message = StringUtils.hasText(expected) ? "管理令牌不正确" : "管理接口未配置 ADMIN_TOKEN";
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(message, ErrorTypes.INVALID_REQUEST));
            return;
        }
        chain.doFilter(request, response);
    }
}
