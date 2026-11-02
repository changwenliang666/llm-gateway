package com.llmgateway.routing;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 模型级 RPM，按滚动 60 秒计算。同一条 Lua 里要么记下这一次，要么完全不记。
 * 开关关闭或 RPM 不是正整数时直接放行，不碰 Redis。TPM 不参与判断。
 */
@Component
public class RedisModelRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisModelRateLimiter.class);
    private static final long WINDOW_TTL_SECONDS = 120L;

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final DefaultRedisScript<Long> admit;

    @Autowired
    public RedisModelRateLimiter(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    public RedisModelRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
        this.admit = script("redis/rate-limit.lua");
    }

    @Override
    public Optional<GatewayException> inspect(ModelRoute route, CallerContext caller, int estimatedTokens,
                                              String reservationId) {
        if (!route.rateLimitEnabled() || limit(route.rpm()) == 0) {
            return Optional.empty();
        }
        Long admitted = eval(admit, window(route), "限流计数不可用",
                Long.toString(clock.millis()),
                Integer.toString(limit(route.rpm())),
                reservationId,
                Long.toString(WINDOW_TTL_SECONDS));
        if (admitted == 0L) {
            return Optional.of(new GatewayException(429, ErrorTypes.RATE_LIMIT, "模型请求超过限制"));
        }
        return Optional.empty();
    }

    private String window(ModelRoute route) {
        return RedisKeys.rateWindow(route.modelId());
    }

    private Long eval(DefaultRedisScript<Long> script, String key, String unavailable, String... args) {
        try {
            Long value = redis.execute(script, List.of(key), (Object[]) args);
            if (value == null) {
                throw new GatewayException(503, ErrorTypes.SERVER_ERROR, unavailable);
            }
            return value;
        } catch (GatewayException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("Redis 限流失败 key={}", key, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, unavailable);
        }
    }

    private static int limit(Integer value) {
        return value == null || value <= 0 ? 0 : value;
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
