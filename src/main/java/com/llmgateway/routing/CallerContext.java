package com.llmgateway.routing;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 限流以后可能用到的调用方维度。本版不解释 clientId，只把它从请求头带过来，
 * 避免以后为了限流再改控制器签名。
 */
public record CallerContext(String remoteAddress, String clientId) {

    public static final String CLIENT_ID_HEADER = "X-Gateway-Client-Id";

    public static CallerContext from(HttpServletRequest request) {
        String clientId = request.getHeader(CLIENT_ID_HEADER);
        if (clientId != null && clientId.isBlank()) {
            clientId = null;
        }
        return new CallerContext(request.getRemoteAddr(), clientId);
    }
}
