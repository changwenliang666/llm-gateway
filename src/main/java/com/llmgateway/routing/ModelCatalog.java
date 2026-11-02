package com.llmgateway.routing;

import java.util.List;

/**
 * 配置读取端口。生产实现走 MyBatis，测试用内存实现，选路逻辑不依赖数据库。
 */
public interface ModelCatalog {

    /** 模型不存在时返回 null。停用模型仍返回，由上层给出 404。 */
    ModelRow findModel(String modelName);

    /** 只包含当前可以参与轮询的账号和 Key，并且已经按 id 升序分组。 */
    List<AccountCandidate> findEligibleAccounts(int modelId, int providerId);
}
