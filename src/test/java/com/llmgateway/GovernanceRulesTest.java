package com.llmgateway;

import com.llmgateway.error.GatewayException;
import com.llmgateway.gateway.ModelAdmission;
import com.llmgateway.routing.CallerContext;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.Permit;
import com.llmgateway.error.ErrorTypes;
import com.llmgateway.routing.AllowAllRateLimiter;
import com.llmgateway.routing.RateLimiter;
import com.llmgateway.routing.TokenUsage;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.InMemoryModelCircuitBreaker;
import com.llmgateway.support.InMemoryModelRateLimiter;
import com.llmgateway.support.MutableClock;
import com.llmgateway.upstream.InvokeResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceRulesTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));

    @Test
    void rpmOnOneModelDoesNotAffectAnotherAndTpmDoesNotCount() {
        InMemoryModelRateLimiter limiter = new InMemoryModelRateLimiter(clock);
        ModelRoute limited = route(1, true, 1, 100);
        ModelRoute other = route(2, true, 1, 100);

        assertTrue(limiter.inspect(limited, caller(), 10, id()).isEmpty());
        GatewayException rejected = limiter.inspect(limited, caller(), 10, id()).orElseThrow();
        assertEquals(429, rejected.status());
        assertEquals("rate_limit_error", rejected.type());
        assertTrue(limiter.inspect(other, caller(), 10, id()).isEmpty());

        ModelRoute tokens = route(3, true, null, 30);
        assertTrue(limiter.inspect(tokens, caller(), 20, id()).isEmpty());
        assertTrue(limiter.inspect(tokens, caller(), 20, id()).isEmpty());
    }

    @Test
    void minuteBoundaryDoesNotResetRpm() {
        clock.advance(Duration.ofSeconds(59));
        InMemoryModelRateLimiter limiter = new InMemoryModelRateLimiter(clock);
        ModelRoute route = route(4, true, 60, null);
        for (int i = 0; i < 60; i++) {
            assertTrue(limiter.inspect(route, caller(), 1, id()).isEmpty());
        }
        clock.advance(Duration.ofSeconds(1));
        assertEquals(429, limiter.inspect(route, caller(), 1, id()).orElseThrow().status());
        clock.advance(Duration.ofSeconds(59));
        assertTrue(limiter.inspect(route, caller(), 1, id()).isEmpty());
    }

    @Test
    void tpmBelowEstimateDoesNotReject() {
        InMemoryModelRateLimiter limiter = new InMemoryModelRateLimiter(clock);
        ModelRoute route = route(5, true, 2, 1);
        assertTrue(limiter.inspect(route, caller(), 20, id()).isEmpty());
        assertTrue(limiter.inspect(route, caller(), 20, id()).isEmpty());
        assertEquals(429, limiter.inspect(route, caller(), 5, id()).orElseThrow().status());
    }

    @Test
    void rateLimitDoesNotCountAsCircuitFailure() {
        InMemoryModelCircuitBreaker breaker = breaker(1);
        breaker.fail(8, 1, breaker.acquire(8, 1));
        clock.advance(Duration.ofSeconds(30));
        Permit probe = breaker.acquire(8, 1);
        assertTrue(probe.probing());
        RateLimiter limiter = (route, caller, estimatedTokens, reservationId) ->
                Optional.of(new GatewayException(429, ErrorTypes.RATE_LIMIT, "模型请求超过限制"));
        ModelAdmission admission = new ModelAdmission(breaker, limiter, TokenUsage.NONE);
        ModelRoute route = route(8, true, 1, null);

        assertEquals(429, assertThrows(GatewayException.class,
                () -> admission.enter(route, 1, probe, caller(), 1)).status());
        Permit again = breaker.acquire(8, 1);
        assertTrue(again.allowed());
        assertTrue(again.probing());
    }

    @Test
    void successfulCallAddsTokensAndFailureDoesNot() {
        AtomicLong total = new AtomicLong();
        ModelAdmission admission = new ModelAdmission(breaker(5), new AllowAllRateLimiter(),
                (modelId, tokens) -> total.addAndGet(tokens));
        ModelRoute route = route(9, false, null, null);

        Permit reported = admission.enter(route, 1, Permit.closed(), caller(), 4);
        admission.finish(route, 1, reported, InvokeResult.buffered(200, "application/json", new byte[0], false),
                4, OptionalInt.of(12));
        assertEquals(12, total.get());

        Permit failed = admission.enter(route, 1, Permit.closed(), caller(), 4);
        admission.finish(route, 1, failed, InvokeResult.buffered(500, "application/json", new byte[0], true),
                4, OptionalInt.empty());
        assertEquals(12, total.get());

        Permit streamed = admission.enter(route, 1, Permit.closed(), caller(), 7);
        admission.finish(route, 1, streamed, InvokeResult.streamed(200), 7, OptionalInt.empty());
        assertEquals(19, total.get());
    }

    @Test
    void circuitOpensHalfOpensAndRecovers() {
        InMemoryModelCircuitBreaker breaker = breaker(2);
        Permit first = breaker.acquire(4, 1);
        breaker.fail(4, 1, first);
        assertTrue(breaker.acquire(4, 1).allowed());
        breaker.fail(4, 1, Permit.closed());

        assertFalse(breaker.acquire(4, 1).allowed());
        assertTrue(breaker.acquire(4, 2).allowed());
        clock.advance(Duration.ofSeconds(30));
        Permit probe = breaker.acquire(4, 1);
        assertTrue(probe.probing());
        assertFalse(breaker.acquire(4, 1).allowed());
        breaker.fail(4, 1, probe);
        assertFalse(breaker.acquire(4, 1).allowed());

        clock.advance(Duration.ofSeconds(30));
        Permit again = breaker.acquire(4, 1);
        assertTrue(again.probing());
        breaker.succeed(4, 1, again);
        Permit restored = breaker.acquire(4, 1);
        assertTrue(restored.allowed());
        assertFalse(restored.probing());
    }

    @Test
    void halfOpenAllowsOnlyOneProbeUnderConcurrency() throws Exception {
        InMemoryModelCircuitBreaker breaker = breaker(1);
        breaker.fail(5, 1, breaker.acquire(5, 1));
        clock.advance(Duration.ofSeconds(30));
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (breaker.acquire(5, 1).allowed()) {
                    admitted.incrementAndGet();
                }
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, admitted.get());
    }

    private InMemoryModelCircuitBreaker breaker(int threshold) {
        return new InMemoryModelCircuitBreaker(clock, threshold, 30_000L, 1, 120_000L);
    }

    private static ModelRoute route(int id, boolean limit, Integer rpm, Integer tpm) {
        return new ModelRoute(id, "m" + id, "openai", "http://upstream", "m" + id,
                List.of(FakeCatalog.account(1, 1)), true, limit, rpm, tpm);
    }

    private static CallerContext caller() {
        return new CallerContext("127.0.0.1", null);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
