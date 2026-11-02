package com.llmgateway.routing;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ModelCatalog} 的 MyBatis 实现。每次请求都读库，不把模型、账号或 Key 缓存在 Redis。
 * 因此把一行改成停用后，下一次新请求就会排除它。同一次请求里的重试用的是已经组好的快照，不会读到一半换池。
 */
@Component
public class DbModelCatalog implements ModelCatalog {

    private final ModelCatalogMapper mapper;

    public DbModelCatalog(ModelCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ModelRow findModel(String modelName) {
        return mapper.selectByName(modelName);
    }

    @Override
    public List<AccountCandidate> findEligibleAccounts(int modelId, int providerId) {
        return AccountPool.group(mapper.selectCandidates(modelId, providerId));
    }
}
