package com.llmgateway.admin;

public class StoredModel {

    private int id;
    private int providerId;
    private String providerName;
    private String modelName;
    private String adapterName;
    private String upstreamModelName;
    private String baseUrl;
    private int enabled = 1;
    private int loadBalanceEnabled = 1;
    private int rateLimitEnabled;
    private Integer rpm;
    private Integer tpm;
    private long consumedTokens;

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

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
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

    public long getConsumedTokens() {
        return consumedTokens;
    }

    public void setConsumedTokens(long consumedTokens) {
        this.consumedTokens = consumedTokens;
    }
}
