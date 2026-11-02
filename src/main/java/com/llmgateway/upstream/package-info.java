/**
 * 上游协议。{@link com.llmgateway.upstream.ProtocolAdapter} 的 {@code name()} 必须和
 * {@code models.adapter_name} 一字不差，{@link com.llmgateway.upstream.AdapterRegistry} 启动时按这个名字收集。
 *
 * <p>适配器只改协议要求的头和模型名，不选账号，也不决定要不要重试。
 * HTTP 都从 {@link com.llmgateway.upstream.UpstreamClient} 出去，超时用编排层传下来的 {@code Duration}。
 * 新供应商：实现这个接口并标成 Spring Bean，再在模型表里写上同一个 {@code adapter_name}。
 */
package com.llmgateway.upstream;
