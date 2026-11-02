package com.llmgateway;

import com.llmgateway.error.GatewayException;
import com.llmgateway.routing.AccountLoadBalancer;
import com.llmgateway.routing.AllowAllCircuitBreaker;
import com.llmgateway.routing.ModelRoute;
import com.llmgateway.routing.RedisKeys;
import com.llmgateway.routing.RoundRobinAccountLoadBalancer;
import com.llmgateway.routing.Selection;
import com.llmgateway.support.FakeCatalog;
import com.llmgateway.support.InMemoryModelCircuitBreaker;
import com.llmgateway.support.InMemoryRotationCursor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoundRobinAccountLoadBalancerTest {

    @Test
    void accountsRotateByGivenOrderAndKeysRotateInsideAnAccount() {
        InMemoryRotationCursor cursor = new InMemoryRotationCursor();
        AccountLoadBalancer balancer = new RoundRobinAccountLoadBalancer(cursor, new AllowAllCircuitBreaker());
        ModelRoute route = ModelRoute.rotating(7, "qwen-max", "openai", "http://upstream", "qwen-max", List.of(
                FakeCatalog.account(10, 1, 2),
                FakeCatalog.account(20, 3),
                FakeCatalog.account(30, 4)));

        assertEquals(10, balancer.select(route).account().accountId());
        assertEquals(20, balancer.select(route).account().accountId());
        assertEquals(30, balancer.select(route).account().accountId());
        Selection again = balancer.select(route);
        assertEquals(10, again.account().accountId());
        assertEquals(2, again.credential().keyId());
        Selection fifth = balancer.select(route);
        assertEquals(20, fifth.account().accountId());
        assertEquals(3, fifth.credential().keyId());

        assertEquals(5L, cursor.values.get(RedisKeys.accountCursor(7)));
        assertTrue(cursor.values.containsKey(RedisKeys.keyCursor(7, 10)));
    }

    @Test
    void accountRetryDoesNotAdvanceTheAccountCursor() {
        InMemoryRotationCursor cursor = new InMemoryRotationCursor();
        AccountLoadBalancer balancer = new RoundRobinAccountLoadBalancer(cursor, new AllowAllCircuitBreaker());
        ModelRoute route = ModelRoute.rotating(7, "qwen-max", "openai", "http://upstream", "qwen-max", List.of(
                FakeCatalog.account(10, 1),
                FakeCatalog.account(20, 2),
                FakeCatalog.account(30, 3)));

        Selection first = balancer.select(route);
        Selection retried = balancer.nextAccount(route, first).orElseThrow();
        Selection secondCall = balancer.select(route);

        assertEquals(0, first.accountIndex());
        assertEquals(1, retried.accountIndex());
        assertEquals(1, secondCall.accountIndex());
        assertEquals(2L, cursor.values.get(RedisKeys.accountCursor(7)));
    }

    @Test
    void disabledLoadBalancePinsTheSmallestAccountAndStillRotatesKeys() {
        InMemoryRotationCursor cursor = new InMemoryRotationCursor();
        AccountLoadBalancer balancer = new RoundRobinAccountLoadBalancer(cursor, new AllowAllCircuitBreaker());
        ModelRoute route = new ModelRoute(7, "qwen-max", "openai", "http://upstream", "qwen-max", List.of(
                FakeCatalog.account(10, 1, 2),
                FakeCatalog.account(20, 3)),
                false, false, null, null);

        Selection first = balancer.select(route);
        Selection second = balancer.select(route);

        assertEquals(10, first.account().accountId());
        assertEquals(1, first.credential().keyId());
        assertEquals(10, second.account().accountId());
        assertEquals(2, second.credential().keyId());
        assertEquals(null, cursor.values.get(RedisKeys.accountCursor(7)));
        assertEquals(2L, cursor.values.get(RedisKeys.keyCursor(7, 10)));
    }

    @Test
    void openAccountIsSkippedUntilEveryAccountIsOpen() {
        InMemoryRotationCursor cursor = new InMemoryRotationCursor();
        InMemoryModelCircuitBreaker breaker = new InMemoryModelCircuitBreaker(
                Clock.systemUTC(), 1, 30_000L, 1, 120_000L);
        breaker.fail(7, 10, breaker.acquire(7, 10));
        AccountLoadBalancer balancer = new RoundRobinAccountLoadBalancer(cursor, breaker);
        ModelRoute route = ModelRoute.rotating(7, "qwen-max", "openai", "http://upstream", "qwen-max", List.of(
                FakeCatalog.account(10, 1),
                FakeCatalog.account(20, 2),
                FakeCatalog.account(30, 3)));

        assertEquals(20, balancer.select(route).account().accountId());
        breaker.fail(7, 20, breaker.acquire(7, 20));
        breaker.fail(7, 30, breaker.acquire(7, 30));
        GatewayException rejected = assertThrows(GatewayException.class, () -> balancer.select(route));
        assertEquals(503, rejected.status());
    }
}
