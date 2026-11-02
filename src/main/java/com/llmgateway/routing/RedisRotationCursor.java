package com.llmgateway.routing;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 轮询计数的 Redis 实现。只用 {@code INCR}，没有过期时间，计数跨进程累加。
 * 连接失败、密码错误或返回空时抛 503「轮询计数不可用」，不在本进程里用 AtomicLong 私下接着数。
 * 否则多实例会各自从 0 开始，同一个账号会被同时打满。
 */
@Component
public class RedisRotationCursor implements RotationCursor {

    private static final Logger log = LoggerFactory.getLogger(RedisRotationCursor.class);

    private final StringRedisTemplate redis;

    public RedisRotationCursor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value == null) {
                throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "轮询计数不可用");
            }
            return value;
        } catch (GatewayException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("Redis 轮询计数失败 key={}", key, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "轮询计数不可用");
        }
    }
}
