## Context

见 `proposal.md` 的 Why。当前生产计数在 `RedisModelRateLimiter` 和 `redis/rate-limit.lua`：键是 `llm-gateway:rl:{modelId}:{yyyyMMddHHmm}`，同一分钟一个哈希里的 `rpm`、`tpm`。`rate-adjust.lua` 按差额改这一分钟的 `tpm`。`InMemoryModelRateLimiter` 用 `RateWindows.minute` 做同样的分桶，给没有 Redis 的 `GovernanceRulesTest` 用。`RedisGovernanceTest` 用本机临时 `redis-server` 和 `MutableClock` 跑真实脚本。判断发生在 `ModelAdmission.enter`，位于选账号之前。补差发生在 `ModelAdmission.finish`，只有成功且适配器读到 `usage.total_tokens` 时才调用。

`openspec/specs/` 下没有已归档的 `model-admission`。未归档变更 `model-governance` 里的同名需求仍写「current one-minute window」。本设计只改限流窗口的实现，不改负载均衡和熔断。因为主规格不存在，增量规格使用 ADDED，而不是 MODIFIED。

## Goals / Non-Goals

**Goals:**

- 滚动 60 秒内的 RPM、TPM 都有硬上限，分钟整点不能再放进第二份满额。
- 多实例仍共用一份计数，读和写在一次 Redis 脚本里完成。
- 测试时钟可以拨动，不调用 Redis 的 `TIME`。
- 内存实现和 Lua 用同一条年龄规则，规则测试不必启动 Redis。

**Non-Goals:**

- 不引入令牌桶或漏桶。
- 不按账号、不按 `X-Gateway-Client-Id` 限流。
- 不改变 429 的状态码、`error.type` 和文案。
- 不改变熔断、轮询、适配器和管理页接口。
- 不把旧分钟桶里的计数迁进新窗口。

## Decisions

### 1. 用按次记录的滑动窗口，不用两个固定桶的加权近似

每次放行在 Redis 里留下一条带时间戳和 Token 权重的记录。判断前先丢掉年龄大于等于 60 秒的记录，再数剩余条数、加剩余权重。

加权双桶（上一分钟剩余比例加当前分钟）实现更短，但在边界上仍可能超过配置值。本变更要消掉的就是「大约两倍」。模型级 RPM 是运营配置的有限整数，一个模型一分钟几千条记录对一次 Lua 足够。

备选是只在 Java 里过滤。多实例会各看各的内存，否决。

### 2. 时间由 Java 的 `Clock` 传入

脚本参数带 `nowMillis`。生产用 `Clock.systemUTC()`，测试继续用 `MutableClock`。脚本里不读 Redis 时间，否则拨钟测试无法证明边界。

年龄规则：分数小于等于 `nowMillis - 60000` 的记录删除。刚好 60 秒的放行不再占用额度。这与规格里「年龄大于等于 60 秒不再计入」一致。

### 3. 键和成员格式

新键是 `llm-gateway:rl:{modelId}`，类型为有序集合。分数是毫秒时间戳。成员是 `{reservationId}|{tokens}`。`reservationId` 由这次 `inspect` 在 Java 侧生成，只在这一次请求内使用。UUID 不含 `|`，Token 是十进制整数。

这个键和旧的 `llm-gateway:rl:{modelId}:{yyyyMMddHHmm}` 不会撞类型。旧键保持原来的 120 秒过期，新代码不读它们。

只限制 RPM 时，成员仍要写入，Token 权重写 0，判断时不比较 TPM。只限制 TPM 时，仍写入成员并累加权重，但不因为条数拒绝。

### 4. 一次脚本里要么写入，要么完全不写

放行脚本先清理过期成员，再计算。RPM 或 TPM 任一会超限就返回 0，不 `ZADD`。都通过才写入并刷新 120 秒过期。Redis 失败仍按现在的方式变成 503「限流计数不可用」，并归还半开名额，不计熔断失败。

### 5. 补差改这一条记录，不改一个合计字段

`RateLimiter.reconcile` 增加这次放行的 `reservationId`。补差脚本按这个 id 找到成员，保留原分数，把 Token 改成 `max(0, 原值 + 实际值 - 估算值)`。成员已经过期或被清理时直接返回，不报错。补差的 Redis 异常仍只记日志。

`ModelAdmission` 在 `enter` 时生成 id，放行后留到 `finish`。限流拒绝或 Redis 失败时没有成员，id 丢掉。`AllowAllRateLimiter` 忽略 id。

估算仍是 `RateWindows.estimate`，至少 1。`RateWindows.minute` 不再参与限流。没有其他调用方时删掉该方法，避免课文和代码继续指向日历分钟。

### 6. 内存实现跟脚本走同一条规则

`InMemoryModelRateLimiter` 按模型保存 `(时间, id, tokens)` 列表，每次 `inspect` 和 `reconcile` 先丢掉年龄大于等于 60 秒的项。继续用方法上的同步，测试是单线程规则。它仍然不要标成 Spring 组件。

## Risks / Trade-offs

- [每次判断都要扫该模型窗口内的全部记录] → 接受。这是模型级额度，不是全局每秒百万次。不预先做分片。
- [上线瞬间计数从 0 开始，旧桶里未过期的额度不会继承] → 接受。切换后最多 120 秒旧键自己消失。回滚到旧版本时，新有序集合也不会被旧脚本读取，额度同样从新的分钟桶重新计。两种切换都不双计。
- [上游调用超过 60 秒之后才补差，成员可能已经过期] → 补差变成空操作。那些 Token 已经不在窗口里，后续判断本来就不该再算它们。成功响应不被改成错误。
- [未归档的 `model-governance` 规格仍写固定窗口] → 主规格不存在，本变更用 ADDED 记录滑动窗口。实施时不要改那份旧变更文件。以后若先归档 `model-governance`，不要让它把固定窗口写回主规格盖过本需求。

## Migration Plan

1. 先合并带新脚本和新键的进程。不写迁移 SQL，没有表结构变化。
2. 部署后新请求只写 `llm-gateway:rl:{modelId}`。
3. 若要回滚，重新部署旧进程即可。旧进程忽略新键。
4. 不需要停机双写。

## Open Questions

无。窗口长度固定为 60 秒，与现在「每分钟」的配置语义一致，不增加新的配置项。
