package com.llmgateway.admin;

import java.util.List;

/**
 * 管理端的写入端口。和只读的模型目录分开，调用链不会在选路时改配置。
 */
public interface AdminStore {

    List<StoredProvider> providers();

    StoredProvider provider(int id);

    void insertProvider(StoredProvider row);

    void updateProvider(StoredProvider row);

    List<StoredAccount> accounts(Integer providerId);

    StoredAccount account(int id);

    void insertAccount(StoredAccount row);

    void updateAccount(StoredAccount row);

    List<StoredKey> keys(int accountId);

    StoredKey key(int id);

    void insertKey(StoredKey row);

    void updateKey(StoredKey row, boolean replaceKey);

    List<StoredModel> models();

    StoredModel model(int id);

    void insertModel(StoredModel row);

    void updateModel(StoredModel row);

    void updateInvocation(StoredModel row);

    void updateLoadBalance(int id, int enabled);

    void updateRateLimit(int id, int enabled, Integer rpm, Integer tpm);

    void replaceAccounts(int modelId, List<Integer> accountIds);

    List<Integer> accountIds(int modelId);
}
