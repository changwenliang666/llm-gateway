package com.llmgateway.upstream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmgateway.routing.Selection;

import java.time.Duration;

/**
 * 一次已经选好账号的上游调用。body 在查询任务时为空。
 * timeout 来自 TimeoutPolicy，适配器只负责遵守它。
 */
public record InvokeCommand(
        AdapterOperation operation,
        Selection selection,
        ObjectNode body,
        Duration timeout,
        boolean stream,
        String taskId
) {
}
