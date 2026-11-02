package com.llmgateway.admin;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminMapper {

    List<StoredProvider> selectProviders();

    StoredProvider selectProvider(@Param("id") int id);

    int insertProvider(StoredProvider row);

    int updateProvider(StoredProvider row);

    List<StoredAccount> selectAccounts(@Param("providerId") Integer providerId);

    StoredAccount selectAccount(@Param("id") int id);

    int insertAccount(StoredAccount row);

    int updateAccount(StoredAccount row);

    List<StoredKey> selectKeys(@Param("accountId") int accountId);

    StoredKey selectKey(@Param("id") int id);

    int insertKey(StoredKey row);

    int updateKey(@Param("row") StoredKey row, @Param("replaceKey") boolean replaceKey);

    List<StoredModel> selectModels();

    StoredModel selectModel(@Param("id") int id);

    int insertModel(StoredModel row);

    int updateModel(StoredModel row);

    int updateInvocation(StoredModel row);

    int updateLoadBalance(@Param("id") int id, @Param("enabled") int enabled);

    int updateRateLimit(@Param("id") int id, @Param("enabled") int enabled,
                        @Param("rpm") Integer rpm, @Param("tpm") Integer tpm);

    int deleteModelAccounts(@Param("modelId") int modelId);

    int insertModelAccount(@Param("modelId") int modelId, @Param("accountId") int accountId);

    List<Integer> selectModelAccountIds(@Param("modelId") int modelId);
}
