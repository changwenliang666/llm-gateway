package com.llmgateway.routing;

/** 测试用的熔断：一律放行，不记状态。生产用 Redis 实现，本类不要标成组件。 */
public class AllowAllCircuitBreaker implements ModelCircuitBreaker {

    @Override
    public Permit acquire(int modelId, int accountId) {
        return Permit.closed();
    }

    @Override
    public void succeed(int modelId, int accountId, Permit permit) {
    }

    @Override
    public void fail(int modelId, int accountId, Permit permit) {
    }

    @Override
    public void release(int modelId, int accountId, Permit permit) {
    }
}
