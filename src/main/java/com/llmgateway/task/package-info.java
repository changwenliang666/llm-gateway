/**
 * 调用记录。{@link com.llmgateway.task.DbTaskBindingStore} 是
 * {@link com.llmgateway.upstream.wan.TaskBindingStore} 的 MySQL 实现。
 * 文本调用和视频创建都插入 {@code model_tasks}；视频查询只按任务号更新状态，并靠它找回创建账号。
 */
package com.llmgateway.task;
