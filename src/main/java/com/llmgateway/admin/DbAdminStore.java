package com.llmgateway.admin;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DbAdminStore implements AdminStore {

    private final AdminMapper mapper;

    public DbAdminStore(AdminMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StoredProvider> providers() {
        return mapper.selectProviders();
    }

    @Override
    public StoredProvider provider(int id) {
        return mapper.selectProvider(id);
    }

    @Override
    public void insertProvider(StoredProvider row) {
        mapper.insertProvider(row);
    }

    @Override
    public void updateProvider(StoredProvider row) {
        mapper.updateProvider(row);
    }

    @Override
    public List<StoredAccount> accounts(Integer providerId) {
        return mapper.selectAccounts(providerId);
    }

    @Override
    public StoredAccount account(int id) {
        return mapper.selectAccount(id);
    }

    @Override
    public void insertAccount(StoredAccount row) {
        mapper.insertAccount(row);
    }

    @Override
    public void updateAccount(StoredAccount row) {
        mapper.updateAccount(row);
    }

    @Override
    public List<StoredKey> keys(int accountId) {
        return mapper.selectKeys(accountId);
    }

    @Override
    public StoredKey key(int id) {
        return mapper.selectKey(id);
    }

    @Override
    public void insertKey(StoredKey row) {
        mapper.insertKey(row);
    }

    @Override
    public void updateKey(StoredKey row, boolean replaceKey) {
        mapper.updateKey(row, replaceKey);
    }

    @Override
    public List<StoredModel> models() {
        return mapper.selectModels();
    }

    @Override
    public StoredModel model(int id) {
        return mapper.selectModel(id);
    }

    @Override
    public void insertModel(StoredModel row) {
        mapper.insertModel(row);
    }

    @Override
    public void updateModel(StoredModel row) {
        mapper.updateModel(row);
    }

    @Override
    public void updateInvocation(StoredModel row) {
        mapper.updateInvocation(row);
    }

    @Override
    public void updateLoadBalance(int id, int enabled) {
        mapper.updateLoadBalance(id, enabled);
    }

    @Override
    public void updateRateLimit(int id, int enabled, Integer rpm, Integer tpm) {
        mapper.updateRateLimit(id, enabled, rpm, tpm);
    }

    @Override
    public void replaceAccounts(int modelId, List<Integer> accountIds) {
        mapper.deleteModelAccounts(modelId);
        for (Integer accountId : accountIds) {
            mapper.insertModelAccount(modelId, accountId);
        }
    }

    @Override
    public List<Integer> accountIds(int modelId) {
        return mapper.selectModelAccountIds(modelId);
    }
}
