package com.llmgateway.gateway;

import com.llmgateway.upstream.InvokeResult;

/**
 * 一次已经拿到 HTTP 状态的调用，要不要记进熔断。
 * 4xx 说明请求到了上游，模型服务本身还在，不算失败，也不算把熔断恢复。
 */
enum CallOutcome {
    SUCCESS,
    FAILURE,
    NEUTRAL;

    static CallOutcome of(InvokeResult result) {
        int status = result.status();
        if (status >= 200 && status < 400) {
            return SUCCESS;
        }
        if (status >= 500) {
            return FAILURE;
        }
        return NEUTRAL;
    }
}
