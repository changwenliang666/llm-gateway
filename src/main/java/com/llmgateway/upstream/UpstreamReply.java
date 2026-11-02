package com.llmgateway.upstream;

import java.io.InputStream;

/**
 * 上游的原始响应。stream 非空时正文还在流里，调用方负责关闭。
 * body 是已经读完的字节，用于非流式和可重试的错误响应。
 */
public record UpstreamReply(int status, byte[] body, InputStream stream) implements AutoCloseable {

    @Override
    public void close() throws java.io.IOException {
        if (stream != null) {
            stream.close();
        }
    }
}
