package com.llmgateway.routing;

import java.util.Optional;

/**
 * 账号和 Key 的选择策略。本版只有平滑轮询一个实现。
 * 以后加权、最少在途或一致性哈希替换这个接口，聊天和视频服务不用改。
 *
 * <p>同一次用户请求里的账号重试必须走 {@link #nextAccount}，它不推进账号游标，
 * 避免一次失败把后面的调用方也跳过一个账号。
 */
public interface AccountLoadBalancer {

    /** 为一次新的用户请求选择账号和 Key，会推进游标。 */
    Selection select(ModelRoute route);

    /**
     * 同一次请求改投下一个账号。没有第二个账号时返回空。
     * 会为新账号取下一把 Key，但不再对账号游标做 INCR。
     */
    Optional<Selection> nextAccount(ModelRoute route, Selection current);

    /** 在指定账号里取下一把 Key。查询视频任务时用来回到创建该任务的账号。 */
    Selection selectKey(ModelRoute route, int accountId);

    /** 同一账号的下一把 Key。只剩一把时返回空，并且不推进游标。 */
    Optional<Selection> nextKey(Selection current);
}
