package com.llmgateway.routing;

/**
 * 一把可用的 API Key。toString 固定打码，避免账号对象被日志框架展开时泄密。
 */
public record ApiCredential(int keyId, String apiKey) {

    @Override
    public String toString() {
        return "ApiCredential[keyId=" + keyId + ", apiKey=****]";
    }
}
