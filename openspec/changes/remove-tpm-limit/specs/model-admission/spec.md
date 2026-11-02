## Purpose

按模型限制滚动 60 秒内的请求数。网关不估算、不执行 TPM，供应商返回的 TPM 错误按上游状态和正文交给调用方。

## ADDED Requirements

### Requirement: Model rate limit counts only RPM
系统 MUST 为每个模型保存限流开关和 RPM。模型上保存的 TPM、调用前估算的 Token、以及成功响应里的实际 Token，都 MUST NOT 参与放行判断，也 MUST NOT 改写已经记下的请求数。开关关闭，或者 RPM 不是正整数时，请求 MUST NOT 消耗该模型的计数。开关打开且 RPM 为正整数时，系统 MUST 在账号已经通过熔断之后拒绝这次请求，如果放行会使该模型在滚动 60 秒内的已放行请求数超过 RPM。窗口 MUST 从当前时刻向前回溯 60 秒，MUST NOT 以日历整分钟重置。年龄大于等于 60 秒的放行 MUST NOT 再计入。拒绝 MUST 返回 HTTP 429，错误类型 MUST 为 `rate_limit_error`，文案 MUST 为「模型请求超过限制」，并且 MUST NOT 改选其他账号。一个模型的计数 MUST NOT 影响另一个模型。调用方标识 MUST NOT 参与这次计数。这次拒绝 MUST NOT 增加该账号的熔断失败次数，也 MUST NOT 把该账号已经拿到的半开试探留下不还。

#### Scenario: RPM on one model
- **WHEN** 模型 A 在滚动 60 秒内已经达到它的 RPM，而模型 B 没有达到
- **THEN** 对 A 的新请求以 HTTP 429 拒绝，对 B 的请求仍然放行

#### Scenario: Configured TPM does not reject
- **WHEN** 模型打开了限流，RPM 仍有余量，并且模型上的 TPM 小于这次能估算出的 Token
- **THEN** 请求不被网关以 TPM 拒绝，上游仍会被调用

#### Scenario: TPM-only configuration does not count
- **WHEN** 限流开关打开，RPM 为空或小于等于 0，TPM 为正整数
- **THEN** 请求放行，并且不写入该模型的限流计数

#### Scenario: Minute boundary does not double the limit
- **WHEN** 模型 RPM 为 60，而且这 60 次放行都发生在日历分钟结束前的最后一秒
- **THEN** 分钟刚切换后的下一次请求仍然以 HTTP 429 拒绝

#### Scenario: Expired admission no longer counts
- **WHEN** 模型 RPM 为 1，唯一一次放行的年龄已经大于等于 60 秒
- **THEN** 新的请求被放行

#### Scenario: Actual tokens do not change the request count
- **WHEN** 一次已放行请求的成功响应带有实际 Token 总量
- **THEN** 滚动窗口里的已放行请求数不变

#### Scenario: Rate limit is not an account failure
- **WHEN** 请求被模型 RPM 拒绝
- **THEN** 该账号熔断器的连续失败次数不增加，随后对这个账号的试探仍可进行

### Requirement: Upstream rate-limit responses stay upstream responses
系统 MUST NOT 因无法估算 Token 而在调用上游之前拒绝请求。上游以 HTTP 429 拒绝，且响应字节尚未写给调用方时，若同一模型的账号池里还有另一个熔断未打开的账号，系统 MUST 改用该账号再请求一次，并且 MUST NOT 因此再消耗一次 RPM。账号池不足两个，或其他账号的熔断都已打开时，系统 MUST NOT 再发上游请求。最终交给调用方的那一次上游 429 MUST 保持该次上游的状态码和正文，MUST NOT 替换成 `rate_limit_error` 或「模型请求超过限制」。上游 429 MUST NOT 增加被调用账号的熔断失败次数。

#### Scenario: Vendor TPM error is returned
- **WHEN** 模型只有一个可用账号，上游对该请求返回 HTTP 429 和它自己的 TPM 错误正文
- **THEN** 调用方收到 HTTP 429 和这份上游正文，正文不是网关的 `rate_limit_error`

#### Scenario: One other account is tried
- **WHEN** 第一次上游响应是 HTTP 429，响应字节还没写给调用方，并且另一个账号的熔断未打开
- **THEN** 网关用另一个账号再请求一次，这一次用户请求的 RPM 只增加 1

#### Scenario: Second upstream error is the caller response
- **WHEN** 第二次上游响应仍是 HTTP 429
- **THEN** 调用方收到第二次的上游状态码和正文

#### Scenario: No healthy account remains
- **WHEN** 第一次上游响应是 HTTP 429，而其他账号的熔断都已打开
- **THEN** 调用方收到第一次的上游状态码和正文，网关不再请求那些账号

### Requirement: Rate limit configuration requires RPM
开启限流时，系统 MUST 要求 RPM 为正整数。只提交 TPM，或 RPM 为空、小于等于 0 时，保存 MUST 失败，限流开关 MUST NOT 被打开。管理界面 MUST NOT 再收集 TPM。已经存放的 TPM 值 MUST NOT 被读出来执行，后续保存 MUST NOT 再把它写成一个有效限制。

#### Scenario: RPM is required to enable the limit
- **WHEN** 管理员打开限流并只填写 TPM
- **THEN** 保存失败，该模型的限流开关保持关闭

#### Scenario: Stored TPM is ignored
- **WHEN** 库里该模型仍有一个正数 TPM，而 RPM 为正整数且未超限
- **THEN** 请求按 RPM 放行，TPM 不产生拒绝
