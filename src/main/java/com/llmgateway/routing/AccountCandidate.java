package com.llmgateway.routing;

import java.util.List;

/** 一个参与轮询的供应商账号，以及它名下已按 id 升序排好的启用 Key。 */
public record AccountCandidate(int accountId, List<ApiCredential> keys) {

    public AccountCandidate {
        keys = List.copyOf(keys);
    }
}
