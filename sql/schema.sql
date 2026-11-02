-- 大模型网关配置表
-- 调用地址只写在 models.base_url，请求原样发到该地址。
-- 同一个模型在 provider_accounts 之间平滑轮询，轮次不入库。

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `model_tasks`;
DROP TABLE IF EXISTS `model_accounts`;
DROP TABLE IF EXISTS `account_keys`;
DROP TABLE IF EXISTS `models`;
DROP TABLE IF EXISTS `provider_accounts`;
DROP TABLE IF EXISTS `providers`;

CREATE TABLE `providers` (
  `id` int NOT NULL AUTO_INCREMENT,
  `provider_name` varchar(255) NOT NULL COMMENT '供应商名称',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_name` (`provider_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `provider_accounts` (
  `id` int NOT NULL AUTO_INCREMENT,
  `provider_id` int NOT NULL COMMENT '供应商id',
  `account_name` varchar(255) NOT NULL COMMENT '账号名称',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_account_name` (`provider_id`, `account_name`),
  CONSTRAINT `fk_accounts_provider_id` FOREIGN KEY (`provider_id`) REFERENCES `providers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `account_keys` (
  `id` int NOT NULL AUTO_INCREMENT,
  `provider_account_id` int NOT NULL COMMENT '供应商账号id',
  `key_name` varchar(255) NOT NULL COMMENT '钥匙备注',
  `api_key` varchar(512) NOT NULL COMMENT 'api_key',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_key_name` (`provider_account_id`, `key_name`),
  UNIQUE KEY `uk_account_api_key` (`provider_account_id`, `api_key`),
  CONSTRAINT `fk_keys_account_id` FOREIGN KEY (`provider_account_id`) REFERENCES `provider_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `models` (
  `id` int NOT NULL AUTO_INCREMENT,
  `provider_id` int NOT NULL COMMENT '供应商id',
  `model_name` varchar(255) NOT NULL COMMENT '调用方使用的模型名',
  `adapter_name` varchar(64) NOT NULL COMMENT '协议适配器：openai、dashscope-wan 等',
  `upstream_model_name` varchar(255) DEFAULT NULL COMMENT '发给上游的模型名，空则用 model_name',
  `base_url` varchar(255) NOT NULL COMMENT '调用地址',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `load_balance_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1在可用账号间轮询 0固定最小账号',
  `rate_limit_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '1启用模型级限流',
  `rpm` int DEFAULT NULL COMMENT '每分钟最大请求数，空或0表示不限制',
  `tpm` int DEFAULT NULL COMMENT '已停用，准入不读取',
  `consumed_tokens` bigint NOT NULL DEFAULT 0 COMMENT '累计消耗的 Token',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_model_name` (`model_name`),
  KEY `idx_models_provider_id` (`provider_id`),
  CONSTRAINT `fk_models_provider_id` FOREIGN KEY (`provider_id`) REFERENCES `providers` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `model_accounts` (
  `model_id` int NOT NULL,
  `provider_account_id` int NOT NULL,
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1参与轮询 0退出',
  PRIMARY KEY (`model_id`, `provider_account_id`),
  KEY `idx_ma_account` (`provider_account_id`),
  CONSTRAINT `fk_ma_model` FOREIGN KEY (`model_id`) REFERENCES `models` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ma_account` FOREIGN KEY (`provider_account_id`) REFERENCES `provider_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `model_tasks` (
  `task_id` varchar(128) NOT NULL COMMENT '上游任务 ID；文本调用没有上游任务号时由网关生成',
  `model_name` varchar(255) NOT NULL COMMENT '调用方使用的模型名',
  `provider_account_id` int NOT NULL COMMENT '创建该任务的供应商账号',
  `task_status` varchar(64) NOT NULL COMMENT '任务状态。视频用上游状态，文本用 SUCCEEDED 或 FAILED',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`task_id`),
  KEY `idx_model_tasks_account` (`provider_account_id`),
  KEY `idx_model_tasks_created_status` (`created_time`, `task_status`),
  CONSTRAINT `fk_model_tasks_account` FOREIGN KEY (`provider_account_id`) REFERENCES `provider_accounts` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
