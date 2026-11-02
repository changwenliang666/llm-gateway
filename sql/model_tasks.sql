-- 给已经建好的库追加任务表，或给旧表补上任务状态。
-- 只动 model_tasks，不删除、不改动原来的五张配置表。
-- 不要改用 sql/schema.sql，那个脚本会先删表。

CREATE TABLE IF NOT EXISTS `model_tasks` (
  `task_id` varchar(128) NOT NULL COMMENT '上游任务 ID；文本调用没有上游任务号时由网关生成',
  `model_name` varchar(255) NOT NULL COMMENT '调用方使用的模型名',
  `provider_account_id` int NOT NULL COMMENT '创建该任务的供应商账号',
  `task_status` varchar(64) NOT NULL COMMENT '任务状态。视频用上游状态，文本用 SUCCEEDED 或 FAILED',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  KEY `idx_model_tasks_account` (`provider_account_id`),
  CONSTRAINT `fk_model_tasks_account` FOREIGN KEY (`provider_account_id`) REFERENCES `provider_accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @task_status_missing = (
    SELECT COUNT(*) = 0
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'model_tasks'
      AND COLUMN_NAME = 'task_status'
);
SET @add_task_status = IF(
    @task_status_missing,
    'ALTER TABLE `model_tasks` ADD COLUMN `task_status` varchar(64) NOT NULL DEFAULT ''UNKNOWN'' COMMENT ''任务状态。视频用上游状态，文本用 SUCCEEDED 或 FAILED'' AFTER `provider_account_id`',
    'SELECT 1'
);
PREPARE add_task_status_stmt FROM @add_task_status;
EXECUTE add_task_status_stmt;
DEALLOCATE PREPARE add_task_status_stmt;
