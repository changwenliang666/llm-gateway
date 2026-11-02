## Context

见 `proposal.md` 的 Why，以及 `specs/model-admission/spec.md`。当前准入在 `ModelAdmission.enter`：账号熔断通过之后，`RedisModelRateLimiter` 执行 `redis/rate-limit.lua`。脚本按 `llm-gateway:rl:{modelId}` 的滚动 60 秒同时判断 RPM 和 TPM，成员是 `{reservationId}|{tokens}`。成功且适配器读到 `usage.total_tokens` 时，`ModelAdmission.finish` 调用 `reconcile`，由 `redis/rate-adjust.lua` 改这一条的 Token 权重。`InMemoryModelRateLimiter` 给没有 Redis 的规则测试用，算法与脚本对齐。

上游 HTTP 429 已经走另一条路。`JsonBodies.retryableStatus` 把 401、429、5xx 标成可重试。`ChatCompletionService` 和 `VideoSynthesisService` 在字节还没写给调用方时调用 `AccountLoadBalancer.nextAccount`。池里至少两个账号、且后面还有熔断未打开的账号时，再请求一次，不再次 `enter`。最终由 `ResponseWriter.writeBuffered` 写下那一次的上游状态码和正文。429 在 `CallOutcome` 里是中性，不计熔断失败。

管理端 `AdminService.ensureRateLimit` 允许只填 RPM 或只填 TPM。页面在 `console/src/views/ModelsView.vue`，构建产物在 `src/main/resources/static/admin`。`models.tpm` 由 `sql/schema.sql` 创建，`sql/model_governance.sql` 是已经使用过的加列脚本。

## Goals / Non-Goals

**Goals:**

- 限流窗口只数请求条数。TPM 配置、估算 Token、实际 Token 都不改变放行结果。
- 上游 429 的状态码和正文继续原样交还，换账号规则不改。
- 管理端不能再把 TPM 存成一个会生效的限制。开启限流必须有正数 RPM。

**Non-Goals:**

- 不把 RPM 改成按账号计数，不改熔断键，不改一次换账号。
- 不新增文生图适配器。文生图和文生视频只是去掉 TPM 的原因，现有对话和视频走同一条准入。
- 不改 `consumed_tokens`。没有真实用量时仍用 `RateWindows.estimate` 做累计，这不是限流。
- 不删除 `models.tpm` 列，不改 `RateLimiter.inspect` 的方法签名，不改写 `sql/model_governance.sql`。

## Decisions

### 1. 滑动窗口只保留条数

`rate-limit.lua` 在清掉年龄大于等于 60 秒的成员后，用 `ZCARD` 判断再加一条会不会超过 RPM。超过就返回 0，不写入。通过则 `ZADD` 成员为 `reservationId`，分数仍是当前毫秒，过期仍是 120 秒。不再读取 TPM 和 Token 参数，也不再把权重拼进成员。

旧成员形如 `{reservationId}|{tokens}`。`ZCARD` 仍把每一条算作一次请求，60 秒内会自然过期。不迁移 Redis 数据。

`InMemoryModelRateLimiter` 同样只保留时间和 `reservationId`。`route.tpm()` 与 `estimatedTokens` 都忽略。RPM 不是正整数，或开关关闭时，直接放行且不记 Redis，与现在「两个上限都无效」的短路一致。

备选是继续写入 `|0`，让旧的补差脚本还能找到成员。补差本身要删掉，保留权重格式只会让人以为 TPM 还在。

### 2. 删掉限流补差

`ModelAdmission.finish` 不再调用 `rates.reconcile`。成功时的 `usage.add` 保持现状：有 `reportedTotalTokens` 用实际值，没有则用估算值。

`RedisModelRateLimiter` 不再加载 `rate-adjust.lua`，并删掉这个脚本。类上的 `reconcile` 覆盖删掉后，接口默认空实现留着，避免改所有测试桩的方法签名。`InMemoryModelRateLimiter.reconcile` 同样删掉。

备选是让 `reconcile` 仍按 id 找成员但什么都不改。那会留下一条不会被调用的 Redis 脚本。

### 3. 上游 TPM 错误不新写一条路径

不改 `retryableStatus`、`nextAccount` 和 `writeBuffered`。单账号或其余账号都已熔断时，调用方拿到的就是这次上游 429 正文。有下一个未熔断账号时仍再试一次，RPM 不加第二次。

网关自己的 RPM 429 仍由 `GatewayException` 写成 `rate_limit_error` / 「模型请求超过限制」，与上游正文分开。测试要锁住这两种正文不会混用。

### 4. 配置层停止接受 TPM

`ensureRateLimit` 只检查 RPM 为正整数，失败文案改为「开启限流时必须填写正数 RPM」。`updateRateLimit` 和 `draft` 忽略请求里的 `tpm`，写入 `null`。读模型时列仍映射，但运行时不使用；页面去掉 TPM 输入，避免把旧值再提交回去。

列留在 `schema.sql`，注释改为已停用、准入不读取。已有库不用再跑迁移。回滚到旧程序时，这列还在，只是被本变更写成 `null` 的行会失去原来的 TPM 数字。

### 5. 文档与管理页产物一起改

课文和 README 里「网关执行 TPM、字节除以 4 占位、成功后补差」改为「只限制 RPM；供应商 TPM 错误按上游正文返回」。`console/` 改完后执行 `npm run build`，产物覆盖 `src/main/resources/static/admin`。桌面 `/Users/changwenliang/Desktop/llm-gateway-课程` 与 `docs/learning-course/` 逐文件一致。

## Risks / Trade-offs

- [只配置了 TPM 的模型会变成不计数] → 这是提案里的破坏性变更。新的保存必须带正数 RPM；运行时把非正数 RPM 当成不限制，即使开关仍为开、列里还有旧 TPM。
- [部署后 60 秒内旧的带权重成员还在] → 条数统计仍然正确，因为每条成员只算一次。不在上线脚本里删键。
- [回滚后新成员没有 Token 权重] → 旧程序的 TPM 会把这些成员当成 0，最多一个窗口内少算 Token。RPM 不受影响。不为此做双写。
- [视频累计用量仍是估算] → 管理页的 `consumed_tokens` 可能偏离供应商账单。它不参与拒绝，文档里分开写。
- [管理页构建产物 diff 大] → 只通过 `npm run build` 生成，不手改 `static/admin` 里的打包文件。

## Migration Plan

1. 先改限流实现和测试，确认 RPM 用例仍拒绝、只填 TPM 的用例会放行并打到上游。
2. 再改管理端校验和页面，并构建静态资源。
3. 最后改 README、课程和桌面副本。
4. 回滚时恢复旧程序即可。不需要删 Redis 键，也不需要加列。已经保存成 `null` 的 TPM 不会自动恢复。
