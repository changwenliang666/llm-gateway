-- 已有库给 model_tasks 增加按创建时间和状态查询的索引。可重复执行。
-- 只动 model_tasks，不修改 providers、provider_accounts、account_keys、models、model_accounts。
-- 不要执行 sql/schema.sql，那个脚本会先删除已有的表。

SET @index_missing = (
    SELECT COUNT(*) = 0
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_tasks'
      AND INDEX_NAME = 'idx_model_tasks_created_status'
);
SET @add_index = IF(
    @index_missing,
    'ALTER TABLE `model_tasks` ADD INDEX `idx_model_tasks_created_status` (`created_time`, `task_status`)',
    'SELECT 1'
);
PREPARE add_index_stmt FROM @add_index;
EXECUTE add_index_stmt;
DEALLOCATE PREPARE add_index_stmt;
