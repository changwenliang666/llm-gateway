package com.llmgateway.routing;

/**
 * 账号在某个模型上的熔断。状态按模型加账号分开，一个账号打开不影响同模型的其他账号。
 * 新的供应商适配器不用实现本接口，选账号时由负载均衡调用它。
 */
public interface ModelCircuitBreaker {

    Permit acquire(int modelId, int accountId);

    void succeed(int modelId, int accountId, Permit permit);

    void fail(int modelId, int accountId, Permit permit);

    /** 这次请求没有打到上游，或结果不能算成功也不能算失败。半开名额要还回去。 */
    void release(int modelId, int accountId, Permit permit);
}
