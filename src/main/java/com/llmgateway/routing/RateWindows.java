package com.llmgateway.routing;

/** 限流窗口和 Token 估算。适配器不参与，新供应商不用改这里。 */
public final class RateWindows {

    /** 从当前时刻往回看的窗口。年龄大于等于这个值的放行不再计入。 */
    public static final long WINDOW_MILLIS = 60_000L;

    private RateWindows() {
    }

    /** 出站 JSON 的粗略 Token 数。调用前还没有上游 usage，先按 4 字节约 1 个 Token 占位。 */
    public static int estimate(byte[] json) {
        if (json == null || json.length == 0) {
            return 1;
        }
        return Math.max(1, json.length / 4);
    }
}
