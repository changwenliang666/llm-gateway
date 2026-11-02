package com.llmgateway.routing;

/**
 * 把一次调用的 Token 累加到模型上。多实例同时更新时由数据库做加法，不在进程里先读再写。
 */
public interface TokenUsage {

    TokenUsage NONE = (modelId, tokens) -> {
    };

    void add(int modelId, long tokens);
}
