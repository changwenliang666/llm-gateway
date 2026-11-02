package com.llmgateway.routing;

/** models 表中的一行。停用的模型也会查出来，由选路决定返回 404。 */
public class ModelRow {

    private int id;
    private int providerId;
    private String modelName;
    private String adapterName;
    private String upstreamModelName;
    private String baseUrl;
    private int enabled;
    /** 缺省开启，和升级前每次请求都轮询一致。管理端显式写 0 才钉住最小账号。 */
    private int loadBalanceEnabled = 1;
    private int rateLimitEnabled;
    private Integer rpm;
    private Integer tpm;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getProviderId() {
        return providerId;
    }

    public void setProviderId(int providerId) {
        this.providerId = providerId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getAdapterName() {
        return adapterName;
    }

    public void setAdapterName(String adapterName) {
        this.adapterName = adapterName;
    }

    public String getUpstreamModelName() {
        return upstreamModelName;
    }

    public void setUpstreamModelName(String upstreamModelName) {
        this.upstreamModelName = upstreamModelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getEnabled() {
        return enabled;
    }

    public void setEnabled(int enabled) {
        this.enabled = enabled;
    }

    public int getLoadBalanceEnabled() {
        return loadBalanceEnabled;
    }

    public void setLoadBalanceEnabled(int loadBalanceEnabled) {
        this.loadBalanceEnabled = loadBalanceEnabled;
    }

    public int getRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(int rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public Integer getRpm() {
        return rpm;
    }

    public void setRpm(Integer rpm) {
        this.rpm = rpm;
    }

    public Integer getTpm() {
        return tpm;
    }

    public void setTpm(Integer tpm) {
        this.tpm = tpm;
    }
}
