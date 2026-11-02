package com.llmgateway.admin;

/** 管理接口里的密钥展示。明文只在写入时进入，读出来永远是掩码。 */
public final class ApiKeys {

    private ApiKeys() {
    }

    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }

    /** 更新时留空或原样送回掩码，表示不换密钥。 */
    public static boolean keepStored(String incoming) {
        return incoming == null || incoming.isBlank() || incoming.startsWith("****");
    }
}
