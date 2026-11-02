## Purpose

按 5 张配置表为每次调用选出启用的模型、供应商账号和 API Key，并在不把轮次写入 MySQL 的前提下做平滑轮询。

## ADDED Requirements

### Requirement: Resolve an enabled model by caller name
The system MUST resolve a caller model name against `models.model_name`. The resolved route MUST include `adapter_name`, `base_url`, and the upstream model name. The upstream model name MUST be `upstream_model_name` when that column is non-blank, and MUST be `model_name` when it is null or blank.

#### Scenario: Enabled model is found
- **WHEN** the caller names a model whose `enabled` is 1
- **THEN** the system resolves that model's adapter, base URL, and upstream model name

#### Scenario: Unknown or disabled model
- **WHEN** the caller names a model that does not exist or whose `enabled` is 0
- **THEN** the system rejects the request with HTTP 404 and does not call an upstream

#### Scenario: Unsupported adapter
- **WHEN** the resolved `adapter_name` is neither `openai` nor `dashscope-wan`
- **THEN** the system rejects the request with HTTP 400 and does not call an upstream

### Requirement: Build the eligible account pool
The system MUST build the account pool from `model_accounts` rows with `enabled` 1 whose `provider_accounts` row has `enabled` 1, the same `provider_id` as the model, and at least one `account_keys` row with `enabled` 1. Accounts or keys that fail any of these conditions MUST be excluded.

#### Scenario: Only fully enabled links are eligible
- **WHEN** a model is linked to one enabled account that has an enabled key, and also linked to a disabled account, a disabled link, an account of another provider, and an account whose keys are all disabled
- **THEN** the pool contains only the enabled account and its enabled keys

#### Scenario: Empty pool
- **WHEN** a model is enabled but its eligible pool is empty
- **THEN** the system rejects the request with HTTP 503 and does not call an upstream

### Requirement: Round-robin accounts and keys without writing rotation to MySQL
For a stable eligible pool, the system MUST order accounts by `provider_accounts.id` ascending and keys by `account_keys.id` ascending. Successive calls for the same model MUST advance one account per call, and successive selections of the same account MUST advance one key per selection. The rotation cursor MUST NOT be inserted or updated in MySQL.

#### Scenario: Accounts rotate in id order
- **WHEN** a model has three eligible accounts and the caller makes three successive calls
- **THEN** the three calls select those accounts in ascending account id order, one each

#### Scenario: Keys rotate inside the selected account
- **WHEN** the selected account has two enabled keys and that account is selected twice
- **THEN** the two selections use those keys in ascending key id order, one each

#### Scenario: Disabled rows stay out after a configuration change
- **WHEN** an account or key is set to `enabled` 0 in MySQL
- **THEN** the next request excludes it without requiring a process restart

### Requirement: Redis holds the cursor and is required for routing
The system MUST store rotation cursors in Redis. It MUST NOT persist them in MySQL. If Redis cannot complete the cursor operation, the system MUST reject the request with HTTP 503 and MUST NOT silently pin every call to the first account.

#### Scenario: Redis is unavailable
- **WHEN** the rotation cursor cannot be read or updated
- **THEN** the system returns HTTP 503 and does not call an upstream

### Requirement: Keep API keys out of caller-visible output
The system MUST NOT return `api_key` values, `Authorization` headers, or key material in response bodies or error messages.

#### Scenario: Routing failure response
- **WHEN** routing rejects a request
- **THEN** the JSON error body contains an error message and does not contain any API key
