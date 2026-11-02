package com.llmgateway.task;

import com.llmgateway.error.ErrorTypes;
import com.llmgateway.error.GatewayException;
import com.llmgateway.upstream.wan.TaskBindingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 把每一次模型调用写进 model_tasks。视频查询按 task_id 取回创建它的账号。
 * 数据库不可用时返回 503，不假装没有记录，否则会轮询到别的账号去查这个任务。
 */
@Component
public class DbTaskBindingStore implements TaskBindingStore {

    private static final Logger log = LoggerFactory.getLogger(DbTaskBindingStore.class);

    private final ModelTaskMapper tasks;

    public DbTaskBindingStore(ModelTaskMapper tasks) {
        this.tasks = tasks;
    }

    @Override
    public void record(String taskId, String modelName, int accountId, String taskStatus) {
        try {
            tasks.insert(taskId, modelName, accountId, taskStatus);
        } catch (DataAccessException ex) {
            log.error("写入模型调用失败 taskId={}", taskId, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "任务绑定不可用");
        }
    }

    @Override
    public void updateStatus(String taskId, String taskStatus) {
        try {
            tasks.updateStatus(taskId, taskStatus);
        } catch (DataAccessException ex) {
            log.error("更新任务状态失败 taskId={}", taskId, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "任务绑定不可用");
        }
    }

    @Override
    public Optional<Integer> findAccountId(String taskId) {
        try {
            return Optional.ofNullable(tasks.findAccountId(taskId));
        } catch (DataAccessException ex) {
            log.error("读取模型调用失败 taskId={}", taskId, ex);
            throw new GatewayException(503, ErrorTypes.SERVER_ERROR, "任务绑定不可用");
        }
    }
}
