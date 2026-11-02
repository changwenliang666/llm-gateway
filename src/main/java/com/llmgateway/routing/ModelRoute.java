package com.llmgateway.routing;

import java.util.List;

/**
 * 一次调用要用的模型快照。accounts 已按供应商账号 id 升序排列。
 * 快照在这次请求里保持不变，重试不会重新查库，因此同一次请求看到的池是稳定的。
 * 负载均衡和限流也取自这一行，避免调用中途有人改开关后，重试和第一次看到不同的策略。
 */
public record ModelRoute(
        int modelId,
        String modelName,
        String adapterName,
        String baseUrl,
        String upstreamModelName,
        List<AccountCandidate> accounts,
        boolean loadBalanceEnabled,
        boolean rateLimitEnabled,
        Integer rpm,
        Integer tpm
) {

    public ModelRoute {
        accounts = List.copyOf(accounts);
    }

    /**
     * 测试和未单独声明治理字段的快照。负载均衡开启、限流关闭，与表上的默认值相同。
     */
    public static ModelRoute rotating(int modelId, String modelName, String adapterName, String baseUrl,
                                      String upstreamModelName, List<AccountCandidate> accounts) {
        return new ModelRoute(modelId, modelName, adapterName, baseUrl, upstreamModelName, accounts,
                true, false, null, null);
    }
}
