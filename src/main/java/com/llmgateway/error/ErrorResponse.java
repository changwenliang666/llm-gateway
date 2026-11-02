package com.llmgateway.error;

/** 网关错误体。字段固定为 error.message 与 error.type，方便调用方和测试识别。 */
public record ErrorResponse(Error error) {

    public record Error(String message, String type) {
    }

    public static ErrorResponse of(String message, String type) {
        return new ErrorResponse(new Error(message, type));
    }
}
