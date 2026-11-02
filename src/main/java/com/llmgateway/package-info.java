/**
 * 大模型网关。调用方只打到本进程，供应商地址和 API Key 都在 MySQL 里。
 *
 * <p>一次请求按固定顺序走，后面的供应商只换其中一环，不要在控制器里写 if：
 *
 * <ol>
 *   <li>{@code web} 收下 HTTP，立刻交给 {@code gateway}。</li>
 *   <li>{@code gateway} 读 JSON，把业务方的 {@code message} 字符串收成上游要的消息数组。</li>
 *   <li>{@code routing} 按模型名读五张配置表，得到适配器名、完整调用地址和可用账号。</li>
 *   <li>{@link com.llmgateway.routing.AccountLoadBalancer} 用 Redis 游标选账号和 Key，并跳过该模型上已经熔断的账号。负载均衡关闭时固定最小账号。</li>
 *   <li>{@link com.llmgateway.gateway.ModelAdmission} 在账号确定后查模型级 RPM。</li>
 *   <li>{@link com.llmgateway.timeout.TimeoutPolicy} 读请求头，决定这一次上游调用能等多久。</li>
 *   <li>{@link com.llmgateway.upstream.ProtocolAdapter} 按 {@code models.adapter_name} 访问上游。
 *       文本走 {@code openai}，视频走 {@code dashscope-wan}。</li>
 *   <li>{@code task} 把这次调用写入 {@code model_tasks}，带上任务状态和用过的账号。</li>
 * </ol>
 *
 * <p>轮次只放 Redis，不写进配置表。配置每次请求现读，停用一个 Key 下一次请求就生效。
 * 启动不写入供应商或模型，也不执行建表脚本，不会删除已有的表。
 */
package com.llmgateway;
