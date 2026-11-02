package com.llmgateway.task;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型调用落库。文本和视频都插入 model_tasks。
 * 同一个 task_id 再次写入时保留第一次的账号和模型名，只更新状态。
 * 列表和汇总只读，不改五张配置表，也不保存轮询游标。
 */
@Mapper
public interface ModelTaskMapper {

    void insert(@Param("taskId") String taskId,
                @Param("modelName") String modelName,
                @Param("accountId") int accountId,
                @Param("taskStatus") String taskStatus);

    void updateStatus(@Param("taskId") String taskId, @Param("taskStatus") String taskStatus);

    Integer findAccountId(@Param("taskId") String taskId);

    List<TaskListRow> page(@Param("modelName") String modelName,
                           @Param("taskStatus") String taskStatus,
                           @Param("accountId") Integer accountId,
                           @Param("from") LocalDateTime from,
                           @Param("toExclusive") LocalDateTime toExclusive,
                           @Param("limit") int limit,
                           @Param("offset") long offset);

    long count(@Param("modelName") String modelName,
               @Param("taskStatus") String taskStatus,
               @Param("accountId") Integer accountId,
               @Param("from") LocalDateTime from,
               @Param("toExclusive") LocalDateTime toExclusive);

    List<TaskStatusCount> countByStatus(@Param("from") LocalDateTime from,
                                        @Param("toExclusive") LocalDateTime toExclusive);

    List<TaskDayCount> countByDay(@Param("from") LocalDateTime from,
                                  @Param("toExclusive") LocalDateTime toExclusive);

    /** 和 created_time 的默认值用同一个数据库时钟，避免 JVM 时区把「今天」算偏。 */
    LocalDate currentDate();
}
