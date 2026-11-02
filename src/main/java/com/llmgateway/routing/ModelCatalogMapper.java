package com.llmgateway.routing;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 配置只读。这里不允许出现 INSERT 或 UPDATE，轮次不入库。
 */
@Mapper
public interface ModelCatalogMapper {

    ModelRow selectByName(@Param("modelName") String modelName);

    List<CandidateRow> selectCandidates(@Param("modelId") int modelId, @Param("providerId") int providerId);
}
