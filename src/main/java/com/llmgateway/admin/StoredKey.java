package com.llmgateway.admin;

public class StoredKey {

    private int id;
    private int providerAccountId;
    private String keyName;
    private String apiKey;
    private int enabled;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(int providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getEnabled() {
        return enabled;
    }

    public void setEnabled(int enabled) {
        this.enabled = enabled;
    }
}
