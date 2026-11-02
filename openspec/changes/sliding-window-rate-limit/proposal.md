## Why

模型级限流现在按 UTC 分钟分桶。整点前后各能放满一次额度，两秒内大约可以打进两倍 RPM 或 TPM。这个边界是当前实现写明的行为，不是偶发故障。调用方会在分钟切换时把共享配额打穿。

## What Changes

- 已开启限流的模型，RPM 和 TPM 改为按滚动 60 秒判断。任意连续 60 秒内，已放行的请求数不得超过 RPM，已计入的 Token 不得超过 TPM。分钟整点不再把计数清零。
- 一次判断仍然同时看 RPM 和 TPM。任一维会超限时，两维都不增加。拒绝仍是 HTTP 429，`error.type` 为 `rate_limit_error`，文案仍是「模型请求超过限制」。拒绝仍发生在选账号之前，不计熔断失败。
- 成功调用后的 Token 补差仍只修正这一次占位，不把已成功的上游结果改成错误。流式和没有用量字段的调用仍保持估算。
- 限流开关、按模型隔离、空或 0 表示该维不限制、调用方标识不参与限流，这些保持不变。熔断、账号轮询、适配器协议不改。
- 旧的按分钟 Redis 键不再读取。上线后滑动窗口从空计数开始，不把旧桶里的数字迁过来。
- 教学文档和 README 里「固定窗口、边界大约两倍」的说法改成滑动窗口的真实行为。桌面上的课程副本与仓库 `docs/learning-course/` 保持一致。

## Capabilities

### New Capabilities

- `model-admission`: 模型级限流改为滚动 60 秒。`openspec/specs/` 里还没有这份主规格，未归档变更 `model-governance` 仍把窗口写成当前这一分钟。主规格不存在时不能使用 MODIFIED，所以本变更用 ADDED 写下滑动窗口需求。负载均衡和熔断不在本变更里重写。若以后先归档 `model-governance`，归档本变更时要避免把固定窗口那句再写回主规格。

### Modified Capabilities

无。

## Impact

- `RedisModelRateLimiter`、`src/main/resources/redis/rate-limit.lua`、`src/main/resources/redis/rate-adjust.lua`、`RedisKeys.rateBucket`、`RateWindows`。
- 与脚本对齐的 `InMemoryModelRateLimiter`，以及 `GovernanceRulesTest`、`RedisGovernanceTest`。
- `RateLimiter` 需要能在补差时找到这一次放行的记录。`ModelAdmission` 是唯一生产调用方。
- 对外 HTTP 路径、429 正文、管理页保存 RPM/TPM 的接口不变。
- 文档：`README.md`，`docs/learning-course/` 中写了固定窗口的课文，以及桌面目录 `/Users/changwenliang/Desktop/llm-gateway-课程`。
