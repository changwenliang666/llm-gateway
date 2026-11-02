package com.llmgateway.upstream;

/**
 * 适配器的结果。
 * committed 为 true 表示字节已经写给调用方，调用链不能再重试，也不能再写一次。
 * retryable 只在还没写出字节、并且上游状态是 401、429 或 5xx 时为 true。
 */
public record InvokeResult(int status, String contentType, byte[] body, boolean retryable, boolean committed) {

    public static InvokeResult buffered(int status, String contentType, byte[] body, boolean retryable) {
        return new InvokeResult(status, contentType, body, retryable, false);
    }

    public static InvokeResult streamed(int status) {
        return new InvokeResult(status, "text/event-stream", null, false, true);
    }
}
