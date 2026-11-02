package com.llmgateway.routing;

/**
 * 负载均衡器选出的账号和 Key。accountIndex 是它在本次快照里的位置，
 * 重试下一个账号时用它加一，不再读 Redis 里的账号游标。
 * circuitPermit 是这次选中账号的熔断放行结果。换账号时各带各的，不能沿用上一个账号的名额。
 */
public record Selection(ModelRoute route, AccountCandidate account, ApiCredential credential, int accountIndex,
                        Permit circuitPermit) {

    @Override
    public String toString() {
        return "Selection[model=" + route.modelName()
                + ", accountId=" + account.accountId()
                + ", keyId=" + credential.keyId() + "]";
    }
}
