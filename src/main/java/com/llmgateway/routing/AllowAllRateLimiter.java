package com.llmgateway.routing;

import com.llmgateway.error.GatewayException;

import java.util.Optional;

/** 测试用的限流：全部放行。生产用 Redis 实现，本类不要标成组件。 */
public class AllowAllRateLimiter implements RateLimiter {

    @Override
    public Optional<GatewayException> inspect(ModelRoute route, CallerContext caller, int estimatedTokens,
                                              String reservationId) {
        return Optional.empty();
    }
}
