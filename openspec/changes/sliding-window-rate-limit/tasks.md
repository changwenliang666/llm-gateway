## 1. 滑动窗口计数

- [x] 1.1 把 `RateLimiter.inspect` 和 `reconcile` 加上这一次放行的 `reservationId`，并改 `AllowAllRateLimiter`、`InMemoryModelRateLimiter`、`RedisModelRateLimiter` 以及 `ModelAdmission` 的调用。验证：全仓库编译通过，限流拒绝时 `ModelAdmission` 仍在选账号前抛出原来的 429，且不调用熔断失败。
- [x] 1.2 重写 `redis/rate-limit.lua`：用调用方传入的毫秒时间清理年龄大于等于 60 秒的成员，RPM 与 TPM 任一会超限则返回 0 且不写入，否则把 `{reservationId}|{tokens}` 写入 `llm-gateway:rl:{modelId}` 并设置 120 秒过期。验证：`RedisGovernanceTest` 里同一时刻打满 RPM 的用例仍然 429，另一个模型不受影响。
- [x] 1.3 重写 `redis/rate-adjust.lua`，只按 `reservationId` 修改这一条记录的 Token，结果不得小于 0；记录已过期则什么都不做。验证：估算与实际用量不同的成功调用之后，下一次判断看到的是调整后的 Token，而不是原来的估算。
- [x] 1.4 `RedisKeys` 改为不带分钟的限流键。若 `RateWindows.minute` 没有剩余调用方，删除它；`estimate` 保留。验证：检索确认限流路径不再格式化 `yyyyMMddHHmm`。

## 2. 边界测试

- [x] 2.1 在 `GovernanceRulesTest` 用 `MutableClock` 增加用例：RPM 为 60 的放行都落在分钟结束前的最后一秒，分钟切过去后下一次仍是 429；把时钟再拨到那些放行年龄大于等于 60 秒，下一次放行。再增加一条：TPM 会超限时 RPM 已用次数不变。验证：`mvn -Dtest=GovernanceRulesTest test` 通过。
- [x] 2.2 在 `RedisGovernanceTest` 用真实 Lua 覆盖同一组边界，包括补差只影响对应的 `reservationId`。验证：`mvn -Dtest=RedisGovernanceTest test` 通过。本机需要有 `redis-server`；测试自己监听 `127.0.0.1:6391`，不使用业务 Redis。

## 3. 文档

- [x] 3.1 更新 `README.md` 的模型级限流一节，写明滚动 60 秒、整点不再重置，并删掉「固定窗口可能放进大约两倍」的现行说明。验证：README 中不再把当前实现称作固定窗口。
- [x] 3.2 更新 `docs/learning-course/` 里把现行限流写成固定窗口的课文，至少包括 `00-课程学习指南.md`、`06-限流熔断与异常处理.md`、`07-核心设计模式与技术原理.md`、`08-源码完整追踪.md`、`10-面试复习与项目讲解.md`。保留四种算法的对比，但写明仓库现在用的是滑动窗口，并改掉「Lua 没有滑动窗口」的练习答案。验证：这些文件不再把当前代码说成按 UTC 分钟清零。
- [x] 3.3 把更新后的 `docs/learning-course/` 同步到 `/Users/changwenliang/Desktop/llm-gateway-课程`。验证：两边同一文件的限流结论一致。

## 4. 自测

- [x] 4.1 运行 `mvn -Dtest=GovernanceRulesTest,RedisGovernanceTest test`。验证：两类测试都通过；若 `redis-server` 不存在，先安装或说明阻塞原因，不得把 Redis 测试标成已通过。
