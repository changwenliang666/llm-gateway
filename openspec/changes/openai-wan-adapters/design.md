## Context

仓库只有 `sql/schema.sql` 里的 5 张表，没有 Java 工程。表注释约定两件事：调用地址只写在 `models.base_url`，请求发到该地址；同一模型在 `provider_accounts` 之间平滑轮询，轮次不入库。行为见 `specs/model-routing`、`specs/openai-chat`、`specs/wan-video`。动机见 `proposal.md`。

百炼文本的 OpenAI 兼容地址形如 `https://{WorkspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1/chat/completions`。万相创建任务使用同一主机上的 `POST /api/v1/services/aigc/video-generation/video-synthesis`，必须带 `X-DashScope-Async: enable`；查询是 `GET /api/v1/tasks/{task_id}`。公开模型清单没有 `wan2.0` 这个 model id，Wan 2 系文生视频当前代表是 `wan2.6-t2v`，Wan 3 的 model id 是 `wan3.0-video`。

目标库是已建好的 MySQL `llm-gateway`（`140.143.144.152:3306`）和 Redis（`140.143.144.152:6379`）。对话里提供过 API Key 和数据库密码，没有提供业务空间 ID。这些值只允许进入本机环境变量或被 git 忽略的本地配置，禁止进入仓库。

## Goals / Non-Goals

**Goals:**

- 一个 Spring Boot 进程完成选路、账号/Key 轮询，以及 `openai` 与 `dashscope-wan` 两种上游协议。
- 启动只读取数据库里的路由，不插入模型，也不改已有表结构。
- 普通测试用模拟上游覆盖规格场景；真实上游冒烟单独打开。

**Non-Goals:**

- 管理接口、控制台、调用日志表和计费。
- 本版不执行具体限流规则，也不更换规格要求的平滑轮询。两者只留扩展点。
- 把 OpenAI 视频协议翻译成万相协议，或把万相协议翻译成 OpenAI 协议。
- 在创建视频后由网关轮询到 `SUCCEEDED` 再响应。
- 缓存模型配置。配置每请求读 MySQL，以便停用立即生效。

## Decisions

### 1. 单体 Maven 工程，Servlet 栈加 JDK HttpClient

使用 JDK 21、Spring Boot 3.5 最新补丁、`mybatis-spring-boot-starter`、MySQL Connector/J、Spring Data Redis（Lettuce）。包名 `com.llmgateway`。Web 层用 `spring-boot-starter-web`，上游用 `java.net.http.HttpClient`。

选择 JDK HttpClient，是因为流式响应要边读边写，不必引入 WebFlux。非流式读完整响应体；`stream=true` 时用 `BodyHandlers.ofInputStream()`，按读到的字节写回客户端并 flush。

超时不写死在适配器里。`TimeoutPolicy` 读取调用方请求头 `X-Gateway-Timeout-Ms`，这是业务方的超时槽位；未传时使用可配置的 `gateway.default-timeout-ms`（初始 120000），避免无头请求一直占着线程。连接超时是基础设施参数 `gateway.connect-timeout-ms`（初始 10000），含在同一次调用预算内。后续按模型或供应商改超时，只替换 `TimeoutPolicy`。调用在业务超时内没有收到上游 HTTP 响应时返回 504，并且不重试，避免把调用方给出的预算用掉两次。

备选是 WebClient。它适合流式，但会把响应式栈带进一个以 MVC 和 MyBatis 为主的进程。

### 2. 一次查出候选池，轮次只放 Redis

路由分两步：

1. 按 `model_name` 读取 `models`。不存在或 `enabled=0` 返回 404。`adapter_name` 不是 `openai` 或 `dashscope-wan` 返回 400。
2. 联表读取候选行：`model_accounts.enabled=1`，账号 `enabled=1` 且 `provider_id` 与模型相同，Key `enabled=1`。按 `provider_accounts.id`、`account_keys.id` 升序。池为空返回 503。

账号游标键为 `llm-gateway:rr:model:{modelId}:account`，Key 游标键为 `llm-gateway:rr:model:{modelId}:account:{accountId}`。每次用户请求对账号游标 `INCR` 一次，下标为 `(值 - 1) % 账号数`。选中账号后再 `INCR` 一次 Key 游标。重试不再 `INCR` 账号游标，而是在同一次快照里取下一个账号，并只为那个账号取下一把 Key。这样并发请求会分到不同账号，重试也不会跳号。

Redis 操作失败返回 503，不允许退化为永远打第一把 Key。MyBatis 映射里不出现对这 5 张表的轮次更新。

网关自身错误体为 `{"error":{"message":"...","type":"..."}}`。`type` 在 400 时为 `invalid_request_error`，404 时为 `not_found`，503 时为 `server_error`。日志可以记录模型名、账号 id、Key id、上游状态码和耗时，禁止记录 `api_key` 和 `Authorization`。

### 3. 适配器只改模型名和协议头，URL 原样使用

`POST` 的 URL 就是 `models.base_url`，不再拼接路径。`model_name` 与 `upstream_model_name` 必须相同，发给上游的 `model` 就是这个名字。

- OpenAI：调用方传 `message` 字符串，网关改成一条 `user` 消息。未传 `thinking` 时补 `{"type":"disabled"}`；调用方已传则保持原样。`Authorization: Bearer {apiKey}`，`Content-Type: application/json`。`stream` 缺省或 false 时透传状态码和 JSON；true 时响应 `Content-Type` 为 `text/event-stream`。
- 万相创建：在上述头之外加 `X-DashScope-Async: enable`。每一次上游调用都插入 `model_tasks`，带上 `task_status`。有 `output.task_id` 时用它做主键，状态用 `output.task_status`；没有任务号时由网关生成 ID，2xx 记 `SUCCEEDED`，其余记 `FAILED`。不写 Redis。同一 `task_id` 再次插入时保留第一次的账号，只更新状态。文本调用同样落这张表。
- 万相查询：`GET /v1/tasks/{task_id}?model=`。目标 URL 取 `base_url` 的 origin，路径换为 `/api/v1/tasks/{task_id}`。`model_tasks` 里有记录则只用该账号的启用 Key；没有记录则走普通轮询。查询失败不改投其他账号。同一账号里若第一把 Key 得到 401、429 或 5xx，可再换该账号的下一把启用 Key 试一次。

创建和聊天在尚未向客户端写出任何字节时，遇到 401、429 或 5xx 才按决策 2 重试一次。其他 4xx 原样返回。

### 4. 启动不写入路由

供应商、账号、Key 和模型都由配置数据维护。进程启动只读取已有行，不插入路由。

调用名必须等于上游真实模型名。文本使用 `openai`，当前有 `qwen-max`、`deepseek-flash`。视频使用 `dashscope-wan`，当前有 `wan2.6-t2v`、`wan3.0-video`。DeepSeek 的对话地址是 `https://api.deepseek.com/chat/completions`。

### 6. 扩展点先于具体策略

调用链固定为：解析模型 → `RateLimiter` → `AccountLoadBalancer` → `TimeoutPolicy` → `ProtocolAdapter`。

- `RateLimiter` 本版的 `AllowAllRateLimiter` 始终放行。以后按模型、账号或调用方限流时替换实现，入口和适配器不动。调用上下文带上可选的 `X-Gateway-Client-Id`，供以后的限流维度使用，本版不解释它。
- `AccountLoadBalancer` 本版只有按规格排序的平滑轮询。以后加权或最少在途替换实现。
- `ProtocolAdapter` 按 `adapter_name` 注册。控制器只按操作类型询问适配器是否支持，不写供应商分支。本版注册 `openai` 与 `dashscope-wan`。未注册的名字返回 400，因此规格里的未知适配器场景仍然成立；接新供应商时增加一个适配器实现即可。

### 5. 连接配置与测试分层

`application.yml` 只引用环境变量。数据库默认主机 `140.143.144.152`、端口 `3306`、库名 `llm-gateway`、用户 `root`，密码仅来自 `MYSQL_PASSWORD`。Redis 默认 `140.143.144.152:6379`，密码来自可选的 `REDIS_PASSWORD`。本地覆盖文件使用 `application-local.yml`，并加入 `.gitignore`。应用不执行带 `DROP TABLE` 的 `sql/schema.sql`，也不启用会改表的迁移。

测试分三层：

- 路由与 JSON 改写用纯单元测试，Redis 用内存计数器替身。
- `@SpringBootTest` 加 Testcontainers 的 MySQL 8 与 Redis，上游用 MockWebServer。覆盖 404/400/503、账号与 Key 顺序、停用立即生效、聊天透传、流式分块、429 后换账号、万相异步头、`task_id` 绑定到创建账号、查询走 `/api/v1/tasks/{task_id}`。Docker 不可用时跳过这一层，单元测试仍然必须通过。
- 真实冒烟仅在 `-Dlive.upstream=true` 且凭据齐全时运行。`qwen-max` 发一条短非流式对话，断言有非空回复。`wan2.0` 使用 `1280*720`、`duration` 2；`wan3.0` 使用 `480P`、`16:9`、`duration` 2。两者都只断言创建响应含 `output.task_id`，并立刻查询一次，断言 HTTP 200 且 `task_status` 属于 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。只有再加 `-Dlive.upstream.await=true` 才每 15 秒轮询，最多 5 分钟。普通 `mvn test` 不得访问真实百炼。

## Risks / Trade-offs

- [上游模型名变更] → `wan2.6-t2v` 与 `qwen-max` 写在可更新的 `models` 行里。冒烟失败时先改上游名，不改协议代码。
- [调用地址或 Key 与模型不一致] → 创建或对话会得到鉴权或模型不存在错误。地址和 Key 以数据库里的配置为准。
- [视频冒烟产生费用] → 默认关闭，参数取该模型允许的短时长和较低分辨率，且不默认等到成片。
- [池大小变化后 `INCR % n` 会跳号] → 接受短暂不均衡，换取并发安全和停用立即生效。不把游标落库。
- [任务记录在 MySQL] → 库里没有对应行时，查询退回普通轮询。单账号部署仍然可用；多账号可能查到别的 Key。已有库用 `sql/model_tasks.sql` 追加这张表，不要重跑会删表的 `sql/schema.sql`。
- [JSON 重序列化可能改变字段顺序] → 规格只要求字段保留，不要求顺序。不支持非 JSON 正文。

## Migration Plan

1. 确认目标库已有配置表。不要重新执行 `sql/schema.sql`。缺 `model_tasks` 时只执行 `sql/model_tasks.sql`。
2. 在 `application-local.yml` 或环境变量中设置 MySQL 与 Redis 连接信息。API Key 写在 `account_keys`，不写进仓库。
3. 启动应用。它只读取已有路由，响应与日志中不出现 Key。
4. 先跑 `mvn test`，再按需打开真实冒烟。
5. 回滚时停止进程即可。若要去掉某个模型，按 `model_name` 删除该行及其 `model_accounts` 关联。
