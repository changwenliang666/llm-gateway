package com.llmgateway.support;

import com.llmgateway.routing.ModelCircuitBreaker;
import com.llmgateway.routing.Permit;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 和 {@code circuit.lua} 同一套状态机，方便没有 Docker 时验证转换。
 * 状态按模型加账号分开。多实例的原子性由 Redis 脚本测试覆盖。
 */
public final class InMemoryModelCircuitBreaker implements ModelCircuitBreaker {

    private final Clock clock;
    private final int failureThreshold;
    private final long openMs;
    private final int maxProbes;
    private final long probeLeaseMs;
    private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();

    public InMemoryModelCircuitBreaker(Clock clock, int failureThreshold, long openMs, int maxProbes, long probeLeaseMs) {
        this.clock = clock;
        this.failureThreshold = failureThreshold;
        this.openMs = openMs;
        this.maxProbes = maxProbes;
        this.probeLeaseMs = probeLeaseMs;
    }

    @Override
    public synchronized Permit acquire(int modelId, int accountId) {
        State state = states.computeIfAbsent(new Key(modelId, accountId), ignored -> new State());
        long now = clock.millis();
        if ("open".equals(state.name)) {
            if (now < state.openedAt + openMs) {
                return Permit.rejected();
            }
            state.name = "half";
            state.probes = 0;
            state.probeUntil = 0;
        }
        if ("half".equals(state.name)) {
            if (state.probes > 0 && now >= state.probeUntil) {
                state.probes = 0;
            }
            if (state.probes >= maxProbes) {
                return Permit.rejected();
            }
            state.probes++;
            state.probeUntil = now + probeLeaseMs;
            return Permit.probe();
        }
        return Permit.closed();
    }

    @Override
    public synchronized void succeed(int modelId, int accountId, Permit permit) {
        State state = states.computeIfAbsent(new Key(modelId, accountId), ignored -> new State());
        state.name = "closed";
        state.failures = 0;
        state.openedAt = 0;
        state.probes = 0;
        state.probeUntil = 0;
    }

    @Override
    public synchronized void fail(int modelId, int accountId, Permit permit) {
        State state = states.computeIfAbsent(new Key(modelId, accountId), ignored -> new State());
        long now = clock.millis();
        if (permit.probing()) {
            open(state, now);
            return;
        }
        state.failures++;
        state.probes = 0;
        state.probeUntil = 0;
        if (state.failures >= failureThreshold) {
            open(state, now);
        }
    }

    @Override
    public synchronized void release(int modelId, int accountId, Permit permit) {
        if (!permit.probing()) {
            return;
        }
        State state = states.get(new Key(modelId, accountId));
        if (state != null && state.probes > 0) {
            state.probes--;
        }
    }

    private static void open(State state, long now) {
        state.name = "open";
        state.failures = 0;
        state.openedAt = now;
        state.probes = 0;
        state.probeUntil = 0;
    }

    private record Key(int modelId, int accountId) {
    }

    private static final class State {
        private String name = "closed";
        private int failures;
        private long openedAt;
        private int probes;
        private long probeUntil;
    }
}
