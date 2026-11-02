package com.llmgateway.support;

import com.llmgateway.routing.AccountCandidate;
import com.llmgateway.routing.ApiCredential;
import com.llmgateway.routing.ModelCatalog;
import com.llmgateway.routing.ModelRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内存配置。账号列表的顺序就是轮询顺序，测试里按 id 升序放入。 */
public final class FakeCatalog implements ModelCatalog {

    private final Map<String, ModelRow> models = new LinkedHashMap<>();
    private final Map<Integer, List<AccountCandidate>> accounts = new LinkedHashMap<>();

    public ModelRow add(String name, String adapter, String upstream, String baseUrl, AccountCandidate... pool) {
        ModelRow row = new ModelRow();
        row.setId(models.size() + 1);
        row.setProviderId(1);
        row.setModelName(name);
        row.setAdapterName(adapter);
        row.setUpstreamModelName(upstream);
        row.setBaseUrl(baseUrl);
        row.setEnabled(1);
        models.put(name, row);
        accounts.put(row.getId(), List.copyOf(Arrays.asList(pool)));
        return row;
    }

    public void clearAccounts(String modelName) {
        accounts.put(models.get(modelName).getId(), List.of());
    }

    public void setAccounts(String modelName, AccountCandidate... pool) {
        accounts.put(models.get(modelName).getId(), List.copyOf(Arrays.asList(pool)));
    }

    public void bind(int modelId, AccountCandidate... pool) {
        accounts.put(modelId, List.copyOf(Arrays.asList(pool)));
    }

    public static AccountCandidate account(int accountId, int... keyIds) {
        List<ApiCredential> keys = new ArrayList<>();
        for (int keyId : keyIds) {
            keys.add(new ApiCredential(keyId, "key-" + keyId));
        }
        return new AccountCandidate(accountId, keys);
    }

    public static AccountCandidate accountWithSecret(int accountId, int keyId, String secret) {
        return new AccountCandidate(accountId, List.of(new ApiCredential(keyId, secret)));
    }

    @Override
    public ModelRow findModel(String modelName) {
        return models.get(modelName);
    }

    @Override
    public List<AccountCandidate> findEligibleAccounts(int modelId, int providerId) {
        return accounts.getOrDefault(modelId, List.of());
    }
}
