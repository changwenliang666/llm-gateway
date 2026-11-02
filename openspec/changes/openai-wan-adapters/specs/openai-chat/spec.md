## Purpose

把调用方的 OpenAI Chat Completions 请求交给 `openai` 适配器，转发到模型表中的完整调用地址，第一版用于 `qwen-max`。

## ADDED Requirements

### Requirement: Accept OpenAI chat completions only for the openai adapter
The system MUST expose `POST /v1/chat/completions`. The JSON body MUST contain `model`. When that model resolves to `adapter_name` `openai`, the system MUST forward the call. When it resolves to another adapter, the system MUST return HTTP 400 and MUST NOT call an upstream.

#### Scenario: Text model is accepted
- **WHEN** the client posts a chat completion whose `model` is an enabled `openai` model
- **THEN** the system forwards it with the OpenAI adapter

#### Scenario: Video model is rejected on the chat endpoint
- **WHEN** the client posts a chat completion whose `model` uses `dashscope-wan`
- **THEN** the system returns HTTP 400 and does not call an upstream

#### Scenario: Missing model field
- **WHEN** the JSON body omits `model` or it is blank
- **THEN** the system returns HTTP 400 and does not call an upstream

### Requirement: Forward the chat request to the configured base URL
The system MUST send `POST` to the model's `base_url` without appending another path. It MUST set `Authorization` to `Bearer` plus the selected API key and `Content-Type` to `application/json`. The caller body MUST contain `message` as a non-blank string. The system MUST replace that field with a `messages` array of one object, `role` `user` and `content` equal to the string, and MUST NOT send the caller `message` field upstream. The caller model name and the upstream model name MUST be the same. When `thinking` is absent or null, the system MUST set it to `{"type":"disabled"}`. When the caller sends `thinking`, the system MUST leave it unchanged. It MUST preserve every other request field except `model`, which MUST be the upstream model name. A missing, blank, or non-string `message` MUST return HTTP 400 and MUST NOT call an upstream.

#### Scenario: A plain message string becomes one user turn
- **WHEN** the client calls `POST /v1/chat/completions` with `model` `qwen-max` and `message` set to a non-blank string
- **THEN** the upstream request URL equals that model's `base_url`, the bearer token is the selected key, `model` is `qwen-max`, and `messages` contains one user message whose content is that string

#### Scenario: Message is not a string
- **WHEN** the chat body omits `message`, or `message` is blank or not a JSON string
- **THEN** the system returns HTTP 400 and does not call an upstream

#### Scenario: Thinking is off unless the caller enables it
- **WHEN** the chat body omits `thinking`
- **THEN** the upstream body contains `thinking.type` `disabled`

#### Scenario: Caller thinking is preserved
- **WHEN** the chat body sets `thinking.type` to `enabled`
- **THEN** the upstream body keeps `thinking.type` `enabled`

#### Scenario: Non-stream success is passed through
- **WHEN** `stream` is absent or false and the upstream returns a 2xx JSON body
- **THEN** the client receives the upstream status code and the upstream JSON body

#### Scenario: Non-retriable upstream error is passed through
- **WHEN** the upstream returns an HTTP 4xx other than 401 or 429
- **THEN** the client receives that status code and body, and the system does not try another account

### Requirement: Retry one later account for auth, rate limit, and server errors
Before any response bytes are written to the client, a chat call that receives HTTP 401, 429, or 5xx MUST be tried once more with the next eligible account and its next key, when such an account exists. The system MUST NOT retry after it has written response bytes to the client.

#### Scenario: Second account succeeds
- **WHEN** the first account returns HTTP 429 and another eligible account exists
- **THEN** the system calls the next account once and returns that second result to the client

#### Scenario: No account left
- **WHEN** the retried call also fails, or no other eligible account exists
- **THEN** the client receives the last upstream status code and body

### Requirement: Stream server-sent events without buffering the full body
When `stream` is true, the system MUST return `Content-Type: text/event-stream` and MUST forward upstream bytes as they arrive. It MUST NOT wait for the upstream stream to finish before sending the first received bytes.

#### Scenario: Chunks are forwarded
- **WHEN** the client sets `stream` to true and the upstream sends multiple SSE chunks
- **THEN** the client receives those chunks in order as a stream

#### Scenario: Failed stream before any body can move to the next account
- **WHEN** `stream` is true, the upstream returns HTTP 500, and no response body has been written to the client
- **THEN** the system retries once with the next eligible account

### Requirement: Do not seed routes on startup
The system MUST NOT insert providers, accounts, keys, or models when the process starts.

#### Scenario: Startup leaves the catalog unchanged
- **WHEN** the process starts
- **THEN** it does not insert a `qwen-max` route and does not require `DASHSCOPE_API_KEY`
