package com.llmgateway.timeout;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;

/**
 * 一次上游调用的超时预算。适配器里不写死秒数。
 * 业务方通过请求头决定；以后按模型或供应商覆盖时，只替换这个接口。
 */
public interface TimeoutPolicy {

    String TIMEOUT_HEADER = "X-Gateway-Timeout-Ms";

    Duration resolve(HttpServletRequest request);
}
