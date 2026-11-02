package com.llmgateway.support;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.routing.CallerContext;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.RateLimiter;
import com.llmgateway.routing.RateWindows;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 和 Redis 限流脚本同一套判断，方便没有 Docker 时跑规则测试。
 * 生产计数仍以 Lua 为准。窗口是滚动 60 秒，只数请求条数。
 */
public final class InMemoryModelRateLimiter implements RateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<Integer, List<Hit>> windows = new ConcurrentHashMap<>();

    public InMemoryModelRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Optional<GatewayException> inspect(ModelRoute route, CallerContext caller, int estimatedTokens,
                                                           String reservationId) {
        int rpmLimit = limit(route.rpm());
        if (!route.rateLimitEnabled() || rpmLimit == 0) {
            return Optional.empty();
        }
        List<Hit> hits = hits(route);
        long now = clock.millis();
        prune(hits, now);
        if (hits.size() + 1 > rpmLimit) {
            return Optional.of(rejected());
        }
        hits.add(new Hit(now, reservationId));
        return Optional.empty();
    }

    private List<Hit> hits(ModelRoute route) {
        return windows.computeIfAbsent(route.modelId(), ignored -> new ArrayList<>());
    }

    private static void prune(List<Hit> hits, long now) {
        hits.removeIf(hit -> hit.at <= now - RateWindows.WINDOW_MILLIS);
    }

    private static int limit(Integer value) {
        return value == null || value <= 0 ? 0 : value;
    }

    private static GatewayException rejected() {
        return new GatewayException(429, ErrorTypes.RATE_LIMIT, "模型请求超过限制");
    }

    private static final class Hit {
        private final long at;
        private final String id;

        private Hit(long at, String id) {
            this.at = at;
            this.id = id;
        }
    }
}
