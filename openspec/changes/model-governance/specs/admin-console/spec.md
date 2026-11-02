## Purpose

让运维在同一个网关进程里维护供应商、账号、密钥和模型，并查看或修改调用地址、负载均衡和限流。密钥不能以明文离开服务端。新的协议适配器注册之后，管理页应能选到它，而不必改页面代码。

## ADDED Requirements

### Requirement: Admin token gates management APIs
The system MUST require header `X-Admin-Token` on `/admin/api/**`. The token MUST match the configured admin token. A missing configuration, a missing header, or a wrong token MUST yield HTTP 401. Caller APIs under `/v1/**` MUST NOT require this header.

#### Scenario: Missing token
- **WHEN** a client calls an admin API without the admin token
- **THEN** the system returns HTTP 401 and does not change stored configuration

#### Scenario: Caller API stays open
- **WHEN** a client calls `/v1/chat/completions` without the admin token
- **THEN** the admin token check is not applied to that request

### Requirement: Manage providers, accounts, keys, and models
The system MUST allow listing and creating providers, provider accounts, account keys, and models, and updating their editable fields, through admin APIs backed by the existing tables. A model MUST store its caller name, provider, base URL, adapter name, upstream model name, and enabled flag. The base URL MUST remain on the model. The adapter name MUST be one of the adapters registered in the running process. Account links for a model MUST stay in `model_accounts` and MUST belong to the model's provider.

#### Scenario: Unknown adapter
- **WHEN** an operator saves a model whose adapter name is not registered
- **THEN** the system rejects the save with HTTP 400

#### Scenario: Account from another provider
- **WHEN** an operator links an account whose provider differs from the model
- **THEN** the system rejects the link with HTTP 400

### Requirement: API keys are masked
Admin responses MUST NOT contain a full API key. The system MUST show only a mask that keeps at most the last four characters. Creating or replacing a key MUST accept the full key in the request body and MUST return only the mask. An update that omits the key or sends the existing mask MUST leave the stored key unchanged.

#### Scenario: List keys
- **WHEN** an operator lists keys for an account
- **THEN** each item contains a mask and does not contain the stored secret

#### Scenario: Update without a new secret
- **WHEN** an operator updates a key name and sends a blank or masked key field
- **THEN** the stored secret is unchanged

### Requirement: Admin pages use the live APIs
The system MUST serve an admin page that can manage providers, accounts, model invocation settings, the load-balance switch, and the model rate limit. The page MUST load and save through the admin APIs and MUST NOT embed fixture rows as the source of truth.

#### Scenario: Adapter list comes from the process
- **WHEN** the admin page asks for available adapters
- **THEN** the response lists the adapters registered in the process, including name and supported operations
