package com.llmgateway.support;

import com.llmgateway.admin.AdminStore;
import com.llmgateway.admin.StoredAccount;
import com.llmgateway.admin.StoredKey;
import com.llmgateway.admin.StoredModel;
import com.llmgateway.admin.StoredProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理服务的内存库。只给测试用，页面不会走到这里。 */
public final class MemoryAdminStore implements AdminStore {

    private int sequence = 1;
    private final Map<Integer, StoredProvider> providers = new LinkedHashMap<>();
    private final Map<Integer, StoredAccount> accounts = new LinkedHashMap<>();
    private final Map<Integer, StoredKey> keys = new LinkedHashMap<>();
    private final Map<Integer, StoredModel> models = new LinkedHashMap<>();
    private final Map<Integer, List<Integer>> links = new LinkedHashMap<>();

    @Override
    public List<StoredProvider> providers() {
        return List.copyOf(providers.values());
    }

    @Override
    public StoredProvider provider(int id) {
        return providers.get(id);
    }

    @Override
    public void insertProvider(StoredProvider row) {
        row.setId(sequence++);
        providers.put(row.getId(), row);
    }

    @Override
    public void updateProvider(StoredProvider row) {
        providers.put(row.getId(), row);
    }

    @Override
    public List<StoredAccount> accounts(Integer providerId) {
        return accounts.values().stream()
                .filter(account -> providerId == null || account.getProviderId() == providerId)
                .toList();
    }

    @Override
    public StoredAccount account(int id) {
        return accounts.get(id);
    }

    @Override
    public void insertAccount(StoredAccount row) {
        row.setId(sequence++);
        accounts.put(row.getId(), row);
    }

    @Override
    public void updateAccount(StoredAccount row) {
        accounts.put(row.getId(), row);
    }

    @Override
    public List<StoredKey> keys(int accountId) {
        return keys.values().stream()
                .filter(key -> key.getProviderAccountId() == accountId)
                .sorted(Comparator.comparingInt(StoredKey::getId))
                .toList();
    }

    @Override
    public StoredKey key(int id) {
        return keys.get(id);
    }

    @Override
    public void insertKey(StoredKey row) {
        row.setId(sequence++);
        keys.put(row.getId(), copyKey(row));
    }

    @Override
    public void updateKey(StoredKey row, boolean replaceKey) {
        StoredKey current = keys.get(row.getId());
        StoredKey updated = copyKey(row);
        if (!replaceKey && current != null) {
            updated.setApiKey(current.getApiKey());
        }
        keys.put(row.getId(), updated);
    }

    @Override
    public List<StoredModel> models() {
        return List.copyOf(models.values());
    }

    @Override
    public StoredModel model(int id) {
        return models.get(id);
    }

    @Override
    public void insertModel(StoredModel row) {
        row.setId(sequence++);
        models.put(row.getId(), row);
        links.putIfAbsent(row.getId(), new ArrayList<>());
    }

    @Override
    public void updateModel(StoredModel row) {
        models.put(row.getId(), row);
    }

    @Override
    public void updateInvocation(StoredModel row) {
        StoredModel current = models.get(row.getId());
        current.setAdapterName(row.getAdapterName());
        current.setUpstreamModelName(row.getUpstreamModelName());
        current.setBaseUrl(row.getBaseUrl());
    }

    @Override
    public void updateLoadBalance(int id, int enabled) {
        models.get(id).setLoadBalanceEnabled(enabled);
    }

    @Override
    public void updateRateLimit(int id, int enabled, Integer rpm, Integer tpm) {
        StoredModel model = models.get(id);
        model.setRateLimitEnabled(enabled);
        model.setRpm(rpm);
        model.setTpm(tpm);
    }

    @Override
    public void replaceAccounts(int modelId, List<Integer> accountIds) {
        links.put(modelId, new ArrayList<>(accountIds));
    }

    @Override
    public List<Integer> accountIds(int modelId) {
        return List.copyOf(links.getOrDefault(modelId, List.of()));
    }

    private static StoredKey copyKey(StoredKey row) {
        StoredKey copy = new StoredKey();
        copy.setId(row.getId());
        copy.setProviderAccountId(row.getProviderAccountId());
        copy.setKeyName(row.getKeyName());
        copy.setApiKey(row.getApiKey());
        copy.setEnabled(row.getEnabled());
        return copy;
    }
}
