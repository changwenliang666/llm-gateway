/**
 * 这一次上游调用可以等多久。业务方用请求头 {@code X-Gateway-Timeout-Ms} 传入，单位毫秒。
 * 不传时用 {@code gateway.default-timeout-ms}。连接阶段另有一个更短的上限，避免地址不可达时一直占着线程。
 */
package com.llmgateway.timeout;
