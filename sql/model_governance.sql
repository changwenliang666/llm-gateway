-- 已有库补模型治理列。只执行一次。
-- 不要执行 sql/schema.sql，那个脚本会先删除已有的表。
-- 默认开启负载均衡，是为了和升级前「每次请求都轮询」保持一致。限流默认关闭。

ALTER TABLE `models`
  ADD COLUMN `load_balance_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '1在可用账号间轮询 0固定最小账号' AFTER `enabled`,
  ADD COLUMN `rate_limit_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '1启用模型级限流' AFTER `load_balance_enabled`,
  ADD COLUMN `rpm` int DEFAULT NULL COMMENT '每分钟最大请求数，空或0表示不限制' AFTER `rate_limit_enabled`,
  ADD COLUMN `tpm` int DEFAULT NULL COMMENT '每分钟最大Token数，空或0表示不限制' AFTER `rpm`;
