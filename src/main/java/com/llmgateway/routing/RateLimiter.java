package com.llmgateway.routing;

import com.llmgateway.error.GatewayException;

import java.util.Optional;

/**
 * 上游调用前的模型级限流。返回空表示放行，返回异常表示拒绝。
 * 不按账号、也不按调用方限流，也不按 Token 限流。estimatedTokens 不参与判断。
 * reservationId 标识这一次放行。
 */
public interface RateLimiter {

    Optional<GatewayException> inspect(ModelRoute route, CallerContext caller, int estimatedTokens,
                                       String reservationId);

    /**
     * 保留空实现。限流窗口不再按 Token 回写。
     */
    default void reconcile(ModelRoute route, String reservationId, int estimatedTokens, int actualTokens) {
    }
}
