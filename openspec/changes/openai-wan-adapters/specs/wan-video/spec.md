## Purpose

把万相视频的创建和查询交给 `dashscope-wan` 适配器，第一版服务调用名 `wan2.0` 与 `wan3.0`，上游分别使用百炼异步视频协议。

## ADDED Requirements

### Requirement: Create a video task only for the wan adapter
The system MUST expose `POST /v1/video/synthesis`. The JSON body MUST contain `model`. When that model resolves to `adapter_name` `dashscope-wan`, the system MUST create an upstream task. When it resolves to another adapter, the system MUST return HTTP 400 and MUST NOT call an upstream. The create call MUST return the upstream response without waiting for the video to finish.

#### Scenario: Wan model is accepted
- **WHEN** the client posts a synthesis body whose `model` is an enabled `dashscope-wan` model
- **THEN** the system forwards it with the wan adapter and returns the upstream create response

#### Scenario: Text model is rejected on the video endpoint
- **WHEN** the client posts a synthesis body whose `model` uses `openai`
- **THEN** the system returns HTTP 400 and does not call an upstream

### Requirement: Forward creation to the model base URL with async headers
The system MUST send `POST` to the model's `base_url` without appending another path. It MUST set `Authorization` to `Bearer` plus the selected API key, `Content-Type` to `application/json`, and `X-DashScope-Async` to `enable`. It MUST preserve every request field except `model`, which MUST be replaced by the upstream model name.

#### Scenario: wan2.0 uses the Wan 2 text-to-video model
- **WHEN** the client creates a task with caller model `wan2.0`
- **THEN** the upstream JSON `model` is `wan2.6-t2v` and `X-DashScope-Async` is `enable`

#### Scenario: wan3.0 uses the Wan 3 video model
- **WHEN** the client creates a task with caller model `wan3.0`
- **THEN** the upstream JSON `model` is `wan3.0-video` and the request URL equals that model's `base_url`

#### Scenario: Create response is not held until completion
- **WHEN** the upstream create call returns a task identifier with a non-terminal task status
- **THEN** the client receives that response without the gateway polling until success

### Requirement: Retry creation once for auth, rate limit, and server errors
Before any response bytes are written to the client, a create call that receives HTTP 401, 429, or 5xx MUST be tried once more with the next eligible account and its next key, when such an account exists. Other HTTP 4xx responses MUST be returned without trying another account.

#### Scenario: Parameter error stays on the first account
- **WHEN** the upstream returns HTTP 400 for an invalid video parameter
- **THEN** the client receives that status and body, and no other account is called

### Requirement: Query a task on the same host with the creating account
The system MUST expose `GET /v1/tasks/{task_id}` and MUST require a `model` query parameter that resolves to `dashscope-wan`. The query request MUST go to the same origin as that model's `base_url`, with path `/api/v1/tasks/{task_id}`, and MUST NOT be posted to the synthesis `base_url`. Every completed video create attempt MUST insert a `model_tasks` row with the caller model name, the creating `provider_account_id`, and `task_status`. When the upstream body contains `output.task_id`, that value MUST be the row's `task_id` and `task_status` MUST be `output.task_status` when present. The system MUST NOT store this binding in Redis. A repeated insert of the same `task_id` MUST keep the first account and MUST update `task_status`. A later query MUST authenticate with an enabled key of that same account while the row exists, and MUST update `task_status` from the query result instead of inserting another row. Text chat completions MUST also insert a `model_tasks` row.

#### Scenario: Query uses the task path and the creating account
- **WHEN** a task was created by one provider account and the client queries that `task_id` with the same caller model
- **THEN** the upstream query URL path is `/api/v1/tasks/{task_id}` on the model host, and the bearer token belongs to the creating account

#### Scenario: Missing memory falls back to normal rotation
- **WHEN** the client queries a `task_id` that has no stored creating account
- **THEN** the system still queries the task path using the model's normal account rotation

#### Scenario: Query model is not a wan model
- **WHEN** the `model` query parameter resolves to an adapter other than `dashscope-wan`
- **THEN** the system returns HTTP 400 and does not call an upstream

### Requirement: Do not seed wan routes on startup
The system MUST NOT insert `wan2.0` or `wan3.0` when the process starts. Those caller names are routable only when configuration data already contains them.

#### Scenario: Startup does not create video models
- **WHEN** the process starts
- **THEN** it does not insert `wan2.0` or `wan3.0`
