package com.llmgateway.support;

import com.llmgateway.upstream.wan.TaskBindingStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用的任务表。同一 task_id 再次写入时保留第一次的账号，状态以最新一次为准。 */
public final class InMemoryTaskBindingStore implements TaskBindingStore {

    public final Map<String, Integer> bindings = new ConcurrentHashMap<>();
    public final Map<String, String> statuses = new ConcurrentHashMap<>();

    @Override
    public void record(String taskId, String modelName, int accountId, String taskStatus) {
        bindings.putIfAbsent(taskId, accountId);
        statuses.put(taskId, taskStatus);
    }

    @Override
    public void updateStatus(String taskId, String taskStatus) {
        if (bindings.containsKey(taskId)) {
            statuses.put(taskId, taskStatus);
        }
    }

    @Override
    public Optional<Integer> findAccountId(String taskId) {
        return Optional.ofNullable(bindings.get(taskId));
    }
}
