## 1. 限流只数 RPM

- [x] 1.1 重写 `src/main/resources/redis/rate-limit.lua`：清理 60 秒外的成员后只用 `ZCARD` 判断 RPM，成员改为 `reservationId`，不再读取 TPM 和 Token。同步改 `RedisModelRateLimiter`，使非正数 RPM 或开关关闭时不访问 Redis。验证：`RedisGovernanceTest.rpmIsIsolatedAndTpmRejects` 改成只填 TPM 时连续 `inspect` 都放行，RPM 为 1 的模型第二次仍是 429，另一个模型不受影响；`mvn -Dtest=RedisGovernanceTest test` 通过。
- [x] 1.2 `InMemoryModelRateLimiter` 按同样规则忽略 `route.tpm()` 和 `estimatedTokens`。把 `GovernanceRulesTest.rpmOnOneModelDoesNotAffectAnotherAndTpmRejects`、`tpmRejectionDoesNotConsumeRpm` 改成：TPM 小于估算值时不拒绝，只填 TPM 时不计数，RPM 仍隔离。验证：`mvn -Dtest=GovernanceRulesTest test` 通过。
- [x] 1.3 删除 `src/main/resources/redis/rate-adjust.lua`。`RedisModelRateLimiter` 和 `InMemoryModelRateLimiter` 去掉 `reconcile` 覆盖，`ModelAdmission.finish` 不再调用它。删掉 `RedisGovernanceTest.minuteBoundaryDoesNotResetAndReconcileTouchesOneReservation` 里按 Token 补差再触发 429 的后半段，分钟边界的 RPM 断言保留。验证：`successfulCallAddsTokensAndFailureDoesNot` 仍按实际 Token 或估算累计 `consumed_tokens`；上述两个测试类通过。

## 2. 上游 TPM 错误原样返回

- [x] 2.1 把 `GatewayHttpTest.tpmLimitRejectsBeforeUpstream` 改成配置了很小的 TPM 且 RPM 未满时，上游仍被调用并返回上游状态。另加一条：只有一个账号、上游 HTTP 429 正文为供应商 TPM 错误时，调用方收到该状态码和该正文，正文不是 `rate_limit_error`。验证：`mvn -Dtest=GatewayHttpTest test` 通过，且 `server.getRequestCount()` 与用例里的上游次数一致。
- [x] 2.2 补一条对话用例：两个账号都返回不同的 HTTP 429 正文，调用方收到第二次的正文，账号游标只增加 1。再补一条：其他账号熔断已打开时，第一次上游 429 直接返回，不再请求那个账号。不改 `JsonBodies.retryableStatus` 和 `nextAccount`。验证：`mvn -Dtest=GatewayHttpTest test` 通过。

## 3. 管理端停止收集 TPM

- [x] 3.1 `AdminService.ensureRateLimit` 只接受正数 RPM，失败文案改为「开启限流时必须填写正数 RPM」。`updateRateLimit` 和 `draft` 忽略请求中的 `tpm` 并写入 `null`。`sql/schema.sql` 里 `models.tpm` 的注释改为已停用、准入不读取；不修改 `sql/model_governance.sql`。验证：`AdminConsoleTest.loadBalanceRequiresTwoSchedulableAccounts` 期望新文案，只提交 TPM 时开关保持关闭；`mvn -Dtest=AdminConsoleTest test` 通过。
- [x] 3.2 从 `console/src/views/ModelsView.vue` 去掉 TPM 输入和「至少填一个」的说明，开启限流时只提交 RPM。在 `console/` 执行 `npm run build`，覆盖 `src/main/resources/static/admin`。验证：源码中不再出现 TPM 输入框；`src/main/resources/static/admin/index.html` 存在。

## 4. 文档

- [x] 4.1 更新 `README.md` 和 `docs/learning-course/` 中仍写着网关执行 TPM、字节估算占位或成功后补差的段落，改为只限制滚动 60 秒 RPM，供应商 TPM 错误按上游正文返回。至少检查 `00-课程学习指南.md`、`01-大模型网关基础认知.md`、`03-核心业务流程.md`、`04-模型路由与适配器.md`、`05-模型调用与响应处理.md`、`06-限流熔断与异常处理.md`、`07-核心设计模式与技术原理.md`、`08-源码完整追踪.md`、`10-面试复习与项目讲解.md`。`consumed_tokens` 仍可写估算累计，并标明它不参与拒绝。验证：上述文件不再把 TPM 描述成网关会执行的限制。
- [x] 4.2 将 `docs/learning-course/` 复制到 `/Users/changwenliang/Desktop/llm-gateway-课程`。验证：`diff -rq` 两边无差异。

## 5. 全量验证

- [x] 5.1 运行 `mvn test`。验证：退出码为 0。`GatewayFlowTest` 在本机没有 Docker 时可以跳过，其余测试必须通过。
