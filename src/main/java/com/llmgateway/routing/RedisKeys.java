package com.llmgateway.routing;

/**
 * 轮询游标的 Redis 键。集中放在这里，避免负载均衡各处自己拼字符串。
 * 视频任务不放 Redis，写在 model_tasks。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String accountCursor(int modelId) {
        return "llm-gateway:rr:model:" + modelId + ":account";
    }

    public static String keyCursor(int modelId, int accountId) {
        return "llm-gateway:rr:model:" + modelId + ":account:" + accountId;
    }

    /** 一个模型一份滑动窗口。成员是每次放行，不是某一分钟的合计。 */
    public static String rateWindow(int modelId) {
        return "llm-gateway:rl:" + modelId;
    }

    /** 一个模型上的一个账号一份熔断状态。 */
    public static String circuit(int modelId, int accountId) {
        return "llm-gateway:cb:" + modelId + ":" + accountId;
    }
}
