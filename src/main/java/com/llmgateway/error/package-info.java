/**
 * 网关自己的错误。业务代码抛 {@link com.llmgateway.error.GatewayException}，
 * {@link com.llmgateway.error.GatewayExceptionHandler} 写成 {@code {"error":{"message","type"}}}。
 * 上游返回的 4xx 和 5xx 不走这里，适配器把状态码和正文原样交还。
 */
package com.llmgateway.error;
