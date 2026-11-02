package com.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关自身的可配置项。供应商、账号、Key 和模型地址都在数据库里，启动时不写入。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 调用方没有给出超时时使用的毫秒数。 */
    private long defaultTimeoutMs = 120_000L;

    /** 与业务超时分开的 TCP 连接超时。 */
    private long connectTimeoutMs = 10_000L;

    /** 管理接口令牌。空字符串表示管理接口全部拒绝，调用方接口不看这个值。 */
    private String adminToken = "";

    private Circuit circuit = new Circuit();

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    public void setCircuit(Circuit circuit) {
        this.circuit = circuit == null ? new Circuit() : circuit;
    }

    /** 模型级熔断的默认参数。状态本身在 Redis，不按模型再存一套。 */
    public static class Circuit {

        private int failureThreshold = 5;
        private long openMs = 30_000L;
        private int halfOpenMaxProbes = 1;
        private long probeLeaseMs = 120_000L;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenMs() {
            return openMs;
        }

        public void setOpenMs(long openMs) {
            this.openMs = openMs;
        }

        public int getHalfOpenMaxProbes() {
            return halfOpenMaxProbes;
        }

        public void setHalfOpenMaxProbes(int halfOpenMaxProbes) {
            this.halfOpenMaxProbes = halfOpenMaxProbes;
        }

        public long getProbeLeaseMs() {
            return probeLeaseMs;
        }

        public void setProbeLeaseMs(long probeLeaseMs) {
            this.probeLeaseMs = probeLeaseMs;
        }
    }
}
