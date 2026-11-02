## Purpose

按模型限制滚动 60 秒内的请求数和 Token 数，避免日历分钟切换时放进两倍额度。限流与供应商协议无关，对话和视频共用同一条准入链。

## ADDED Requirements

### Requirement: Model rate limits use a sliding 60-second window
系统 MUST 为每个模型保存限流开关、RPM 和 TPM。开关关闭时，请求 MUST NOT 消耗该模型的计数。开关打开时，系统 MUST 在选择账号之前拒绝一次请求，如果放行会使该模型在滚动 60 秒内的已放行请求数超过已配置的 RPM，或使同一窗口内计入的 Token 超过已配置的 TPM。窗口 MUST 从当前时刻向前回溯 60 秒，MUST NOT 以日历整分钟重置。年龄大于等于 60 秒的放行 MUST NOT 再计入。RPM 与 TPM MUST 在同一次判断里一起生效：任一维会超限时，两维都 MUST NOT 增加。拒绝 MUST 返回 HTTP 429，错误类型 MUST 为 `rate_limit_error`，文案 MUST 为「模型请求超过限制」，并且 MUST NOT 改选其他账号。一个模型的计数 MUST NOT 影响另一个模型。打开开关时，RPM 与 TPM MUST 至少有一个为正整数。空值或小于等于 0 MUST 表示这一维不限制。调用方标识 MUST NOT 参与这次计数。成功响应里如果带有这一次的实际 Token 总量，系统 MUST 只修正这一次放行所计入的 Token，修正后的值 MUST NOT 小于 0。没有实际用量时，窗口内 MUST 保持放行时的估算。补差失败 MUST NOT 把已经成功的调用改成错误。

#### Scenario: RPM on one model
- **WHEN** 模型 A 在滚动 60 秒内已经达到它的 RPM，而模型 B 没有达到
- **THEN** 对 A 的新请求以 HTTP 429 拒绝，对 B 的请求仍然放行

#### Scenario: TPM rejection
- **WHEN** 放行所估算的 Token 会使模型在滚动 60 秒内的 Token 超过 TPM
- **THEN** 请求以 HTTP 429 拒绝，上游不被调用

#### Scenario: Minute boundary does not double the limit
- **WHEN** 模型 RPM 为 60，而且这 60 次放行都发生在日历分钟结束前的最后一秒
- **THEN** 分钟刚切换后的下一次请求仍然以 HTTP 429 拒绝

#### Scenario: Expired admission no longer counts
- **WHEN** 模型 RPM 为 1，唯一一次放行的年龄已经大于等于 60 秒
- **THEN** 新的请求被放行

#### Scenario: One dimension does not move when the other rejects
- **WHEN** 这次放行会使 TPM 超限，而 RPM 仍有余量
- **THEN** 请求以 HTTP 429 拒绝，并且 RPM 的已用次数不增加

#### Scenario: Rate limit is not a model failure
- **WHEN** 请求被模型限流拒绝
- **THEN** 该模型熔断器的连续失败次数不增加

#### Scenario: Actual tokens replace only this admission
- **WHEN** 一次已放行请求的估算 Token 与成功响应中的实际总量不同
- **THEN** 滚动窗口里只调整这一次放行的 Token，随后的判断看到调整后的总量
