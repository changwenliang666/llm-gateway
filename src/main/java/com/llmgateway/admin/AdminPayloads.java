package com.llmgateway.admin;

import java.util.List;

/** 管理接口的请求和响应。响应里没有 apiKey 字段，避免序列化时把明文带出去。 */
public final class AdminPayloads {

    private AdminPayloads() {
    }

    public record ProviderBody(String providerName) {
    }

    public record ProviderView(int id, String providerName) {
    }

    public record AccountBody(Integer providerId, String accountName, Integer enabled) {
    }

    public record AccountView(int id, int providerId, String accountName, int enabled) {
    }

    public record KeyBody(String keyName, String apiKey, Integer enabled) {
    }

    public record KeyView(int id, int providerAccountId, String keyName, String apiKeyMask, int enabled) {
    }

    public record ModelBody(Integer providerId, String modelName, String adapterName, String upstreamModelName,
                            String baseUrl, Integer enabled, Boolean loadBalanceEnabled, Boolean rateLimitEnabled,
                            Integer rpm, Integer tpm, List<Integer> accountIds) {
    }

    public record InvocationBody(String adapterName, String upstreamModelName, String baseUrl) {
    }

    public record SwitchBody(Boolean enabled) {
    }

    public record RateLimitBody(Boolean enabled, Integer rpm, Integer tpm) {
    }

    public record ModelView(int id, int providerId, String providerName, String modelName, String adapterName,
                            String upstreamModelName, String baseUrl, int enabled, boolean loadBalanceEnabled,
                            boolean rateLimitEnabled, Integer rpm, Integer tpm, long consumedTokens,
                            int eligibleAccounts, List<Integer> accountIds) {
    }

    public record AdapterView(String name, String label, List<String> operations) {
    }
}
