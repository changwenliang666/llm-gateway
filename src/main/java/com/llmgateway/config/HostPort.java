package com.llmgateway.config;

/**
 * 解析 {@code host} 或 {@code host:port}。端口缺省时用调用方给出的默认端口。
 * 不做 DNS，也不接受空主机名。
 */
public record HostPort(String host, int port) {

    public static HostPort parse(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        String trimmed = value.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon < 0) {
            return new HostPort(trimmed, defaultPort);
        }
        String host = trimmed.substring(0, colon).trim();
        String portText = trimmed.substring(colon + 1).trim();
        if (host.isEmpty() || portText.isEmpty()) {
            throw new IllegalArgumentException("服务器地址格式应为 host 或 host:port");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("端口不是整数");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口超出范围");
        }
        return new HostPort(host, port);
    }
}
