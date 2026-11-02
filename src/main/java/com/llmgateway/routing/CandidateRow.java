package com.llmgateway.routing;

/**
 * 联表后的扁平行。一个账号有多把 Key 时会出现多行，分组时再收成账号。
 * apiKey 的 toString 不参与日志，调用方禁止打印本对象。
 */
public class CandidateRow {

    private int accountId;
    private int keyId;
    private String apiKey;

    public int getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    public int getKeyId() {
        return keyId;
    }

    public void setKeyId(int keyId) {
        this.keyId = keyId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
