/**
 * 选路。回答两个问题：这个模型名能不能用，以及这一次该用哪个账号、哪把 Key。
 *
 * <p>{@link com.llmgateway.routing.RouteResolver} 读 MySQL，做成一份这次请求不再变化的快照。
 * {@link com.llmgateway.routing.RoundRobinAccountLoadBalancer} 用 Redis 的 {@code INCR} 在这份快照上轮询。
 * 配置表里没有轮次字段，所以停用账号不会把计数写坏，只会让下标跳过一个位置。
 */
package com.llmgateway.routing;
