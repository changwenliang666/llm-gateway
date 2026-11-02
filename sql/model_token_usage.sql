-- 已有库补 models.consumed_tokens。不要执行 sql/schema.sql，那个脚本会先删表。
ALTER TABLE `models`
  ADD COLUMN `consumed_tokens` bigint NOT NULL DEFAULT 0 COMMENT '累计消耗的 Token' AFTER `tpm`;
