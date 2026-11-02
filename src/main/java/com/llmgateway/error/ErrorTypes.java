package com.llmgateway.error;

/** 网关自己产生的错误类型。上游返回的正文不套这套类型，原样交还调用方。 */
public final class ErrorTypes {

    public static final String INVALID_REQUEST = "invalid_request_error";
    public static final String NOT_FOUND = "not_found";
    public static final String SERVER_ERROR = "server_error";
    public static final String RATE_LIMIT = "rate_limit_error";

    private ErrorTypes() {
    }
}
