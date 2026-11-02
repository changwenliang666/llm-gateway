package com.llmgateway.timeout;

import com.llmgateway.config.GatewayProperties;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 读取 {@code X-Gateway-Timeout-Ms}。未传时用配置里的默认预算。
 * 非法数值直接拒绝，不拿默认值悄悄替换，避免业务方以为自己的超时生效了。
 */
@Component
public class HeaderTimeoutPolicy implements TimeoutPolicy {

    private final GatewayProperties properties;

    public HeaderTimeoutPolicy(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Duration resolve(HttpServletRequest request) {
        String header = request.getHeader(TIMEOUT_HEADER);
        if (header == null || header.isBlank()) {
            return Duration.ofMillis(properties.getDefaultTimeoutMs());
        }
        long millis;
        try {
            millis = Long.parseLong(header.trim());
        } catch (NumberFormatException ex) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "超时必须是正整数毫秒");
        }
        if (millis <= 0) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "超时必须是正整数毫秒");
        }
        return Duration.ofMillis(millis);
    }
}
