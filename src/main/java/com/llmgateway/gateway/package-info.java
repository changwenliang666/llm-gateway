/**
 * 编排层。控制器进来之后，真正的顺序写在这里：校验正文、选路、限流、选账号、调用适配器、记任务、必要时换账号再试一次。
 *
 * <p>适配器不知道重试。负载均衡不知道供应商协议。两边只通过
 * {@link com.llmgateway.upstream.InvokeCommand} 和 {@link com.llmgateway.upstream.InvokeResult} 交接。
 * 文本和视频各有一个服务，是因为视频查询必须回到创建任务的那个账号，对话没有这个约束。
 */
package com.llmgateway.gateway;
