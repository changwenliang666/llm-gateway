package com.llmgateway;

import com.llmgateway.config.GatewayProperties;
import com.llmgateway.routing.CallerContext;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.Permit;
import com.llmgateway.routing.RedisModelCircuitBreaker;
import com.llmgateway.routing.RedisModelRateLimiter;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着真实 Redis 跑限流和熔断脚本。使用本机 redis-server 的临时端口，不碰业务 Redis。
 */
class RedisGovernanceTest {

    private static Process redisProcess;
    private static final String host = "127.0.0.1";
    private static final int port = 6391;

    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;
    private MutableClock clock;

    @BeforeAll
    static void startRedis() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                "redis-server", "--port", Integer.toString(port), "--bind", "127.0.0.1",
                "--protected-mode", "no", "--save", "", "--appendonly", "no", "--daemonize", "no");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        redisProcess = builder.start();
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 200);
                return;
            } catch (java.io.IOException ex) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("临时 Redis 没有在 6391 端口起来");
    }

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void connect() {
        factory = new LettuceConnectionFactory(host, port);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @AfterAll
    static void shutdown() {
        if (redisProcess != null) {
            redisProcess.destroy();
        }
    }

    @Test
    void rpmIsIsolatedAndTpmDoesNotCount() {
        RedisModelRateLimiter limiter = new RedisModelRateLimiter(template, clock);
        ModelRoute limited = route(1, 1, null);
        assertTrue(limiter.inspect(limited, caller(), 10, id()).isEmpty());
        assertEquals(429, limiter.inspect(limited, caller(), 10, id()).orElseThrow().status());
        assertTrue(limiter.inspect(route(2, 1, null), caller(), 10, id()).isEmpty());

        ModelRoute tokens = route(3, null, 30);
        assertTrue(limiter.inspect(tokens, caller(), 20, id()).isEmpty());
        assertTrue(limiter.inspect(tokens, caller(), 20, id()).isEmpty());
    }

    @Test
    void minuteBoundaryDoesNotResetRpm() {
        RedisModelRateLimiter limiter = new RedisModelRateLimiter(template, clock);
        clock.advance(Duration.ofSeconds(59));
        ModelRoute limited = route(4, 60, null);
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.inspect(limited, caller(), 1, id()).isEmpty());
        }
        clock.advance(Duration.ofSeconds(1));
        assertEquals(429, limiter.inspect(limited, caller(), 1, id()).orElseThrow().status());
        clock.advance(Duration.ofSeconds(59));
        assertTrue(limiter.inspect(limited, caller(), 1, id()).isEmpty());
    }

    @Test
    void tpmDoesNotRejectWhileRpmStillCounts() {
        RedisModelRateLimiter limiter = new RedisModelRateLimiter(template, clock);
        ModelRoute route = route(7, 2, 1);
        assertTrue(limiter.inspect(route, caller(), 20, id()).isEmpty());
        assertTrue(limiter.inspect(route, caller(), 20, id()).isEmpty());
        assertEquals(429, limiter.inspect(route, caller(), 5, id()).orElseThrow().status());
    }

    @Test
    void concurrentRpmAdmitsOnlyOne() throws Exception {
        RedisModelRateLimiter limiter = new RedisModelRateLimiter(template, clock);
        ModelRoute route = route(9, 1, null);
        assertEquals(1, admitted(12, () -> limiter.inspect(route, caller(), 1, id()).isEmpty()));
    }

    @Test
    void circuitFollowsTheThreeStatesAndLimitsProbes() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.getCircuit().setFailureThreshold(2);
        properties.getCircuit().setHalfOpenMaxProbes(1);
        RedisModelCircuitBreaker breaker = new RedisModelCircuitBreaker(template, properties, clock);

        breaker.fail(4, 1, breaker.acquire(4, 1));
        breaker.fail(4, 1, breaker.acquire(4, 1));
        assertFalse(breaker.acquire(4, 1).allowed());
        Permit sibling = breaker.acquire(4, 2);
        assertTrue(sibling.allowed());
        assertFalse(sibling.probing());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(1, admitted(8, () -> breaker.acquire(4, 1).allowed()));

        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        breaker.fail(4, 1, breaker.acquire(4, 1));
        breaker.fail(4, 1, breaker.acquire(4, 1));
        clock.advance(Duration.ofSeconds(30));
        Permit probe = breaker.acquire(4, 1);
        assertTrue(probe.probing());
        breaker.succeed(4, 1, probe);
        Permit restored = breaker.acquire(4, 1);
        assertTrue(restored.allowed());
        assertFalse(restored.probing());

        breaker.fail(4, 1, Permit.closed());
        breaker.fail(4, 1, Permit.closed());
        clock.advance(Duration.ofSeconds(30));
        Permit failedProbe = breaker.acquire(4, 1);
        breaker.fail(4, 1, failedProbe);
        assertFalse(breaker.acquire(4, 1).allowed());
    }

    private static int admitted(int threads, java.util.function.BooleanSupplier attempt) throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (attempt.getAsBoolean()) {
                    admitted.incrementAndGet();
                }
            });
            workers[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread worker : workers) {
            worker.join();
        }
        return admitted.get();
    }

    private static ModelRoute route(int id, Integer rpm, Integer tpm) {
        return new ModelRoute(id, "m" + id, "openai", "http://upstream", "m" + id,
                List.of(FakeCatalog.account(1, 1)), true, true, rpm, tpm);
    }

    private static CallerContext caller() {
        return new CallerContext("127.0.0.1", null);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
