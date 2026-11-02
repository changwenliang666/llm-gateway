package com.llmgateway.upstream.wan;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 创建任务用模型表里的完整地址。查询任务只借用这个地址的主机，
 * 路径换成百炼的 /api/v1/tasks/{task_id}，不会把查询 POST 回合成地址。
 */
public final class TaskUris {

    private TaskUris() {
    }

    public static URI query(String baseUrl, String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.indexOf('/') >= 0 || taskId.indexOf('\\') >= 0) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "任务 ID 不合法");
        }
        URI base = URI.create(baseUrl);
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                    "/api/v1/tasks/" + taskId, null, null);
        } catch (URISyntaxException ex) {
            throw new GatewayException(400, ErrorTypes.INVALID_REQUEST, "模型调用地址无法用于任务查询");
        }
    }
}
