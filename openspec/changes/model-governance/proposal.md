## Why

网关已经能按模型把文本和视频请求转到供应商，但没有管理界面，限流是空放行，也没有熔断。多账号轮询一直开着，不能按模型关掉。后续要接 Google 和字节，这些能力必须挂在模型上，而不是写进某一个供应商适配器。

## What Changes

- 增加管理接口和静态管理页：供应商、账号与 Key、模型、调用配置、负载均衡开关、模型级限流。API Key 只以掩码返回。
- 模型增加负载均衡开关。关闭时固定使用当前可调度池里 id 最小的账号，不推进账号游标。开启时沿用现有轮询，且可调度账号少于两个时拒绝开启。
- 增加模型级 RPM / TPM 限流，用 Redis Lua 在调用前原子计数。超限返回 429，不换账号，不记入熔断。
- 增加模型级三态熔断，状态放在 Redis。限流拒绝和选路基础设施故障不记为调用失败。
- 保留现有的一次换账号（401 / 429 / 5xx 且尚未向调用方写出字节）。这不是新的故障转移。
- 新供应商继续实现 `ProtocolAdapter` 并注册为 Bean。管理页的适配器列表来自注册表，不在页面里写死供应商。

## Capabilities

### New Capabilities

- `model-admission`: 模型级负载均衡开关、RPM/TPM 限流和三态熔断，以及它们在调用链上的顺序。
- `admin-console`: 管理接口、Key 掩码和管理页。管理接口使用 `X-Admin-Token`。

### Modified Capabilities

## Impact

- `models` 增加 `load_balance_enabled`、`rate_limit_enabled`、`rpm`、`tpm`。已有库用单独的 ALTER，不重跑会删表的 `schema.sql`。
- 对话和视频编排在选账号前增加熔断和限流。`AllowAllRateLimiter` 不再作为生产实现。
- 调用方可见的新错误：限流 429，`type` 为 `rate_limit_error`；熔断 503，文案「模型暂时不可用」。
- 管理接口在 `/admin/api/**`，页面在 `/admin/`。未配置 `ADMIN_TOKEN` 时管理接口拒绝访问，`/v1/**` 不受影响。
