package com.llmgateway.support;

import com.llmgateway.routing.RotationCursor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用游标。语义与 Redis INCR 相同：第一次返回 1。 */
public final class InMemoryRotationCursor implements RotationCursor {

    public final Map<String, Long> values = new ConcurrentHashMap<>();

    @Override
    public long increment(String key) {
        return values.merge(key, 1L, Long::sum);
    }
}
