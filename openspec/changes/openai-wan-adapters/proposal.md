## Why

网关目前只有 5 张配置表，还不能把调用转到上游模型。第一版需要先跑通文本和视频两条协议：通义千问文本走 OpenAI 兼容协议，万相视频走百炼异步协议，并按表里的账号做平滑轮询。

## What Changes

- 新建 JDK 21 + Spring Boot 3.5 + MyBatis + MySQL + Redis 的网关进程。配置表为 `providers`、`provider_accounts`、`account_keys`、`models`、`model_accounts`。调用记录写入 `model_tasks`。启动不改表结构，也不插入路由。
- 按 `models.adapter_name` 选择协议适配器。`openai` 用于文本模型；`dashscope-wan` 用于万相视频。调用地址只使用 `models.base_url`。文本调用方传 `message` 字符串，网关改成一条 user 消息；未传 `thinking` 时默认 `{"type":"disabled"}`。`model_name` 与 `upstream_model_name` 必须相同。
- 同一模型在启用的供应商账号之间平滑轮询，账号内再在启用的 API Key 之间轮询。轮次只放 Redis，不入库。
- 已接入且调用名与上游名相同的模型：`qwen-max`、`deepseek-flash`、`wan2.6-t2v`、`wan3.0-video`。
- 数据库和 Redis 地址、密码只从环境变量或被忽略的 `application-local.yml` 读取，不写入仓库。
- 用模拟上游完成可重复的自主测试；真实上游冒烟测试单独开关，默认不在普通测试里调用付费接口。

## Capabilities

### New Capabilities

- `model-routing`: 从 5 张表解析启用的模型、账号和 Key，并用 Redis 做平滑轮询。
- `openai-chat`: 以 OpenAI Chat Completions 协议代理 `adapter_name=openai` 的文本模型，包含非流式与流式透传。调用方传 `message` 字符串，默认关闭思考。
- `wan-video`: 以百炼万相异步协议代理 `adapter_name=dashscope-wan` 的模型，包含创建任务和查询任务。任务记录在 `model_tasks`。

### Modified Capabilities

- 无

## Impact

- 新增 Maven 工程、MyBatis 映射、Redis 轮询、两个上游适配器和对外 HTTP 接口。
- 依赖已建好的 MySQL 库 `llm-gateway` 与 Redis。应用不执行 `sql/schema.sql` 里的删表语句。
- 对外新增 `POST /v1/chat/completions`、`POST /v1/video/synthesis`、`GET /v1/tasks/{task_id}`。
- 不包含管理后台、调用审计、计费，以及其他供应商适配器。
