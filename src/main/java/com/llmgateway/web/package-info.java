/**
 * HTTP 入口。两个控制器只认路径，把请求原样交给 gateway。
 *
 * <p>{@link com.llmgateway.web.ChatCompletionController} 接 {@code POST /v1/chat/completions}。
 * {@link com.llmgateway.web.VideoController} 接视频创建和任务查询。
 * 返回值是 {@code void}，因为流式对话要边收边写，不能等服务把正文收成一个对象再由 Spring 序列化。
 */
package com.llmgateway.web;
