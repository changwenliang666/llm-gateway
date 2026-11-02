package com.llmgateway.routing;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只负责累加 models.consumed_tokens。配置的其余列仍由管理接口修改。 */
@Mapper
public interface TokenUsageMapper {

    int add(@Param("modelId") int modelId, @Param("tokens") long tokens);
}
