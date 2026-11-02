package com.llmgateway.upstream.wan;

import java.util.Optional;

/**
 * 每一次模型调用的落库入口。文本和视频都写 model_tasks。
 * 视频查询靠 task_id 找回创建它的账号，因为百炼的任务属于那个账号。
 */
public interface TaskBindingStore {

    /**
     * 记下一次调用。task_id 已存在时保留第一次的账号和模型名，只更新状态。
     */
    void record(String taskId, String modelName, int accountId, String taskStatus);

    void updateStatus(String taskId, String taskStatus);

    Optional<Integer> findAccountId(String taskId);
}
