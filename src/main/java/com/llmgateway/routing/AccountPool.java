package com.llmgateway.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把联表查出来的扁平行收成「一个账号带着多把 Key」。
 * SQL 已经按账号 id、Key id 升序。{@code LinkedHashMap} 按第一次见到的账号 id 记住顺序，
 * 后面同一账号的行只追加 Key。轮询下标就打在这个顺序上，Java 不再排序。
 * 例如两行 (账号 3, Key 1)、(账号 3, Key 2) 收成账号 3 的两把 Key；下一行账号 5 成为第二个账号。
 */
public final class AccountPool {

    private AccountPool() {
    }

    public static List<AccountCandidate> group(List<CandidateRow> rows) {
        Map<Integer, List<ApiCredential>> grouped = new LinkedHashMap<>();
        for (CandidateRow row : rows) {
            grouped.computeIfAbsent(row.getAccountId(), ignored -> new ArrayList<>())
                    .add(new ApiCredential(row.getKeyId(), row.getApiKey()));
        }
        List<AccountCandidate> accounts = new ArrayList<>(grouped.size());
        grouped.forEach((accountId, keys) -> accounts.add(new AccountCandidate(accountId, keys)));
        return accounts;
    }
}
