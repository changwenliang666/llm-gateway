## 1. 工程骨架

- [x] 1.1 建立 JDK 21 与 Spring Boot 3.5 最新补丁的 Maven 工程，引入 Web、MyBatis、MySQL Connector/J 和 Spring Data Redis，包名 `com.llmgateway`。验证：`mvn -q -DskipTests package` 成功，且依赖树中没有 WebFlux。
- [x] 1.2 用环境变量配置 MySQL 与 Redis。主机默认 `140.143.144.152`，MySQL 端口 `3306`、库 `llm-gateway`、用户 `root`，Redis 端口 `6379`。密码只读 `MYSQL_PASSWORD` 和可选的 `REDIS_PASSWORD`。把 `application-local.yml` 加入 `.gitignore`。验证：被跟踪文件中没有 API Key，也没有数据库密码字面量。

## 2. 模型路由

- [x] 2.1 用 MyBatis 实现模型查询和候选池联表查询：只保留启用的模型链接、同一供应商下启用的账号，以及启用的 Key，并按账号 id、Key id 升序。不更新这 5 张表。验证：针对固定夹具数据的查询测试能区分不存在、停用、空池和混合启用状态。
- [x] 2.2 用 Redis `INCR` 实现账号与 Key 轮询，键名按 design.md 决策 2。同一次请求的重试使用快照中的下一个账号，不再次递增账号游标。Redis 失败返回 503。验证：三个账号连续三次调用按 id 轮转，同一账号的两把 Key 按 id 轮转，Redis 失败时不访问上游。
- [x] 2.3 网关自身错误返回 `{"error":{"message","type"}}`：未知或停用模型 404，适配器不支持或缺少 `model` 400，无可用账号 503。验证：上述响应的测试断言状态码，且响应体不包含夹具中的 API Key。

## 3. OpenAI 适配器

- [x] 3.1 实现 `openai` 适配器：`POST` 到 `base_url`，设置 Bearer 与 `Content-Type`。调用方传 `message` 字符串，网关改成一条 user 消息后再替换 JSON 的 `model`。非流式透传上游状态码和正文。验证：MockWebServer 收到的 `qwen-max` 请求含该字符串组成的 `messages`，上游模型名为 `qwen-max`，2xx 与非 401/429 的 4xx 原样返回。
- [x] 3.2 实现 `stream=true` 的边读边写，响应 `text/event-stream`。写出任何字节后不再重试。验证：测试客户端先收到第一块 SSE，再收到后续块，而不是等上游结束后一次返回。
- [x] 3.3 暴露 `POST /v1/chat/completions`。`dashscope-wan` 模型返回 400。在未向客户端写字节时，401、429 和 5xx 用下一个账号重试一次。验证：第一个账号返回 429、第二个账号返回 200 时，客户端得到第二次的正文，且上游只被多调用一次。

## 4. 万相适配器

- [x] 4.1 实现 `dashscope-wan` 创建：`POST` 到 `base_url`，附加 `X-DashScope-Async: enable`，`wan2.0` 的上游模型名为 `wan2.6-t2v`，`wan3.0` 为 `wan3.0-video`。创建响应立即返回，网关不轮询到完成。验证：MockWebServer 断言请求头、上游模型名，以及客户端收到的是创建响应而不是最终视频地址。
- [x] 4.2 每一次文本调用和视频创建都写入 `model_tasks`，包含 `task_status`。视频有 `output.task_id` 时用它做主键并记下上游状态；没有任务号时生成 ID，成功记 `SUCCEEDED`，失败记 `FAILED`。不写 Redis。同一任务再次写入仍保留第一次的账号，只更新状态。查询只更新状态。验证：按 `task_id` 能查到创建账号 id 和状态。
- [x] 4.3 暴露 `POST /v1/video/synthesis` 与 `GET /v1/tasks/{task_id}?model=`。查询 URL 使用模型主机加 `/api/v1/tasks/{task_id}`。有绑定则只用该账号的 Key；无绑定则走普通轮询。参数类 4xx 不换账号。验证：两个账号时，查询使用创建账号的 Bearer，请求路径不是 synthesis 地址；`openai` 模型走视频接口时返回 400。

## 5. 初始路由

- [x] 5.1 启动不写入供应商、账号、Key 或模型。路由只读数据库里已经配好的行，不读取 `DASHSCOPE_API_KEY` 做初始插入。验证：进程启动日志里没有「百炼初始路由已写入」。

## 6. 自主测试

- [x] 6.1 用 Testcontainers 的 MySQL 8 与 Redis 做 `@SpringBootTest`，串起选路、聊天、流式、重试、万相创建与查询。Docker 不可用时跳过该层并保留明确提示。验证：Docker 可用时该测试类通过；不可用时 `mvn test` 仍因单元测试通过而成功。
- [x] 6.2 增加默认关闭的真实冒烟测试。`-Dlive.upstream=true` 时调用 `qwen-max` 短对话，以及 `wan2.0`（`1280*720`、2 秒）和 `wan3.0`（`480P`、`16:9`、2 秒）的创建与一次查询。只有 `-Dlive.upstream.await=true` 才继续轮询，间隔 15 秒，最多 5 分钟。验证：不带该属性的 `mvn test` 不访问百炼；凭据和业务空间 ID 齐全时再执行一次 live 测试并记录三个模型的结果。
- [x] 6.3 确认应用不会执行 `sql/schema.sql` 中的删表语句。验证：代码与启动配置中没有运行该脚本，集成测试前后夹具表仍在。
