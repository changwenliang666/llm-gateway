## Why

网关现在按出站 JSON 字节估算 Token，并在滚动窗口里执行 TPM。文生图和文生视频没有稳定的 Token 用量，这个估算会把还能打到供应商的请求提前拒掉。供应商自己的 TPM 错误已经带了真实原因，网关不需要再算一遍。

## What Changes

- **BREAKING**：网关不再执行 TPM。配置里的 `tpm` 不再参与放行。只填了 TPM、没有正数 RPM 的模型，限流开关即使开着也不再按 Token 拒绝。
- 限流只保留每个模型滚动 60 秒的 RPM。超限仍返回 HTTP 429，`error.type` 为 `rate_limit_error`，文案仍是「模型请求超过限制」，不计熔断失败。
- 网关不再为限流估算 Token，也不再按 `usage.total_tokens` 回写限流窗口。
- 上游返回的 429，包括供应商 TPM 错误，保持上游的状态码和正文交给调用方，不改写成网关自己的限流 JSON。已有的「未写出字节时换一个账号再试一次」保持不变；两次都是上游 429 时，调用方看到的是第二次的上游正文。
- 管理页去掉 TPM 输入。开启限流时必须填写正数 RPM。`models.tpm` 列保留，读写都不再把它当成限制。
- `consumed_tokens` 的累计方式不改。熔断、账号选择、RPM 滑动窗口不改。

## Capabilities

### New Capabilities

- `model-admission`: 模型准入只按滚动 60 秒 RPM 限流，并原样交还上游 TPM/429。`openspec/specs/` 里还没有这份主规格，未归档的 `model-governance` 和 `sliding-window-rate-limit` 仍要求执行 TPM。主规格不存在时不能使用 MODIFIED，所以本变更用 ADDED 写下去掉 TPM 之后的准入需求。归档时不能把那两份变更里的 TPM 判断再写回主规格。

### Modified Capabilities

无。

## Impact

- `RedisModelRateLimiter`、`src/main/resources/redis/rate-limit.lua`、`src/main/resources/redis/rate-adjust.lua`、`InMemoryModelRateLimiter`、`ModelAdmission`。
- `AdminService`、`console/src/views/ModelsView.vue`，以及 `AdminConsoleTest`、`GovernanceRulesTest`、`RedisGovernanceTest`、`GatewayHttpTest`。
- `ModelRoute.tpm()` 可以留在快照上，但准入和限流脚本不得读取它做判断。
- 文档：`README.md`，`docs/learning-course/` 里写了 TPM 限流、估算和补差的课文，以及桌面目录 `/Users/changwenliang/Desktop/llm-gateway-课程`。
