package com.llmgateway.error;

/**
 * 还没有把上游响应写给调用方时，用这个异常结束请求。
 * message 会返回给调用方，因此不能带 API Key、请求头或上游地址里的凭据。
 */
public class GatewayException extends RuntimeException {

    private final int status;
    private final String type;

    public GatewayException(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }
}
