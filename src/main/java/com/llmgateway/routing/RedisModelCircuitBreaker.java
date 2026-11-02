package com.llmgateway.routing;

import com.llmgateway.config.GatewayProperties;
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

/**
 * 账号熔断的 Redis 实现。键是模型加账号，一次状态变化都在 {@code circuit.lua} 里完成。
 * 半开名额带租约：拿到名额的进程如果中途退出，租约结束后别的请求可以再试。
 */
@Component
public class RedisModelCircuitBreaker implements ModelCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisModelCircuitBreaker.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties.Circuit settings;
    private final Clock clock;
    private final DefaultRedisScript<Long> script;

    @Autowired
    public RedisModelCircuitBreaker(StringRedisTemplate redis, GatewayProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    public RedisModelCircuitBreaker(StringRedisTemplate redis, GatewayProperties properties, Clock clock) {
        this.redis = redis;
        this.settings = properties.getCircuit();
        this.clock = clock;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new org.springframework.core.io.ClassPathResource("redis/circuit.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public Permit acquire(int modelId, int accountId) {
        long code = eval(modelId, accountId, "acquire",
                Long.toString(clock.millis()),
                Long.toString(settings.getOpenMs()),
                Integer.toString(Math.max(1, settings.getHalfOpenMaxProbes())),
                Long.toString(settings.getProbeLeaseMs()));
        if (code == 2L) {
            return Permit.probe();
        }
        if (code == 1L) {
            return Permit.closed();
        }
        return Permit.rejected();
    }

    @Override
    public void succeed(int modelId, int accountId, Permit permit) {
        eval(modelId, accountId, "success");
    }

    @Override
    public void fail(int modelId, int accountId, Permit permit) {
        eval(modelId, accountId, "failure",
                Long.toString(clock.millis()),
                Integer.toString(Math.max(1, settings.getFailureThreshold())),
                permit.probing() ? "1" : "0");
    }

    @Override
    public void release(int modelId, int accountId, Permit permit) {
        if (!permit.probing()) {
            return;
        }
        eval(modelId, accountId, "release");
    }

    private long eval(int modelId, int accountId, String command, String... extra) {
        String key = RedisKeys.circuit(modelId, accountId);
        Object[] args = new Object[extra.length + 1];
        args[0] = command;
        System.arraycopy(extra, 0, args, 1, extra.length);
        try {
            Long value = redis.execute(script, List.of(key), args);
            if (value == null) {
                throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "熔断状态不可用");
            }
            return value;
        } catch (GatewayException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.error("Redis 熔断失败 key={} command={}", key, command, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "熔断状态不可用");
        }
    }
}
