## Purpose

让运维用同一个网关上的管理控制台维护供应商、账号、密钥和模型，并查看调用任务与 Token、任务量统计。统计只读已有累计值和任务记录，不改变调用链。

## ADDED Requirements

### Requirement: Console exposes six views at the admin path
The system MUST serve the admin console at `/admin/`. After the operator supplies a valid admin token, the console MUST provide these views: 概览、供应商、账号与密钥、模型、调用任务、Token 消耗. Configuration and statistics shown in those views MUST come from `/admin/api/**`. The console MUST NOT use embedded fixture rows or sample chart series as the source of truth.

#### Scenario: Open the console
- **WHEN** an operator opens `/admin/`
- **THEN** the system returns the console shell, and caller APIs under `/v1/**` remain unchanged

#### Scenario: Empty data stays empty
- **WHEN** the admin APIs return no providers, models, or tasks
- **THEN** the console shows empty or zero values and does not draw placeholder statistics

### Requirement: Console keeps the admin token on each request
The console MUST ask for the admin token before showing configuration or statistics. Every `/admin/api/**` request it sends MUST include header `X-Admin-Token`. When an admin API returns HTTP 401, the console MUST hide configuration and statistics and MUST show the error message from the response. Choosing to replace the token MUST stop later requests from sending the previous token until the operator enters a new one.

#### Scenario: Rejected token
- **WHEN** the operator submits a token and the next admin API returns HTTP 401
- **THEN** the console does not show provider, account, model, task, or usage data, and it shows the response error message

#### Scenario: Token replaced
- **WHEN** the operator chooses to replace the token and has not entered a new one
- **THEN** the console returns to the token prompt and does not call admin APIs with the previous token

### Requirement: Console manages providers, accounts, keys, and models
The console MUST let an operator list, create, and update providers, accounts, account keys, and models through the existing admin APIs. A model update MUST include invocation settings (adapter name, upstream model name, base URL), the load-balance switch, the rate-limit switch with RPM and TPM, and the linked account ids. The adapter choices MUST come from `GET /admin/api/adapters`. The console MUST show only `apiKeyMask` for a stored key. It MUST send a new secret only when the operator types one; a blank secret field or the current mask MUST be submitted so the stored secret stays unchanged. When an admin API returns HTTP 400, the console MUST show `error.message` and MUST leave the last successfully loaded values in place.

#### Scenario: Create and update a provider
- **WHEN** the operator creates a provider and then changes its name
- **THEN** the console calls the provider create and update APIs and shows the name returned by those APIs

#### Scenario: Key list is masked
- **WHEN** the operator opens keys for an account
- **THEN** each row shows the mask from the API and does not show the stored secret

#### Scenario: Update key without a new secret
- **WHEN** the operator changes a key name and leaves the secret field blank
- **THEN** the console submits the update without a new secret and the displayed mask stays the one returned by the API

#### Scenario: Rejected model save
- **WHEN** the operator saves a model and the API returns HTTP 400
- **THEN** the console shows `error.message` and does not present the rejected values as saved

### Requirement: Operators can page through call tasks
The system MUST provide `GET /admin/api/tasks` for operators. The response MUST be JSON with `total`, `page`, `size`, and `items`. Each item MUST contain `taskId`, `modelName`, `accountId`, `accountName`, `taskStatus`, and `createdTime`. `createdTime` MUST use the form `yyyy-MM-dd'T'HH:mm:ss` from the stored creation timestamp. Items MUST be ordered by creation time descending, then `taskId` descending. The API MUST NOT return request or response bodies. It MUST NOT create, update, or delete tasks.

Query parameters `modelName`, `taskStatus`, and `accountId` are optional exact filters. `from` and `to` are optional inclusive calendar dates `yyyy-MM-dd` on the creation timestamp. `page` defaults to 1 and `size` defaults to 20. `page` less than 1, `size` outside 1 through 100, or a malformed date MUST yield HTTP 400. A filter that matches nothing MUST yield HTTP 200 with `total` 0 and an empty `items` array. A missing or wrong admin token MUST yield HTTP 401.

The 调用任务 view MUST show this page as a table and MUST offer the same filters and page controls. The table MUST use the API page rather than downloading every task into the browser.

#### Scenario: First page
- **WHEN** an operator requests `GET /admin/api/tasks` with a valid admin token and no filters
- **THEN** the response is HTTP 200, `page` is 1, `size` is 20, `items` has at most 20 rows, and `total` is the count of all stored tasks

#### Scenario: Filter by status
- **WHEN** an operator requests the task list with `taskStatus` set
- **THEN** every item has that status and `total` counts only matching tasks

#### Scenario: Invalid page size
- **WHEN** an operator requests the task list with `size` greater than 100
- **THEN** the system returns HTTP 400 and does not return a task page

#### Scenario: Console table follows the page
- **WHEN** the operator opens 调用任务 and more tasks exist than one page
- **THEN** the table shows only the current page and a control loads the next page from the API

### Requirement: Operators can chart task volume
The system MUST provide `GET /admin/api/tasks/summary`. Optional `from` and `to` are inclusive dates `yyyy-MM-dd`. When both are omitted, the range MUST be the 14 calendar days ending today, using the calendar date of each stored creation timestamp. When only `to` is present, `from` MUST default to 13 days before `to`. When only `from` is present, `to` MUST default to today. `from` after `to`, a malformed date, or a range longer than 90 days MUST yield HTTP 400.

The response MUST contain `from`, `to`, `byStatus`, and `byDay`. `byStatus` lists each status that has at least one task in the range, with `status` and `count`. `byDay` MUST list every date from `from` through `to` in ascending order, including dates whose `count` is 0. A task MUST be counted on its creation date, including after a later status change. A missing or wrong admin token MUST yield HTTP 401.

概览 MUST chart `byStatus` and `byDay` from this API. The charts MUST update when the summary response changes and MUST NOT keep a previous range after a failed refresh.

#### Scenario: Default window
- **WHEN** an operator requests the summary with no dates
- **THEN** `from` and `to` span 14 calendar days ending today, and `byDay` has 14 entries

#### Scenario: Status update does not move the day
- **WHEN** a task was created on a day inside the range and its status changes later
- **THEN** the summary still counts that task on the original creation date

#### Scenario: Overview charts use the summary
- **WHEN** the operator opens 概览 and the summary returns counts
- **THEN** the status chart and the daily chart show those counts

### Requirement: Operators can chart cumulative token usage
Token charts MUST use `consumedTokens` and `providerName` from `GET /admin/api/models`. 概览 MUST chart tokens by model and tokens by provider for every returned model. Token 消耗 MUST let the operator filter by provider and then by model. The filtered total MUST equal the sum of `consumedTokens` of the shown models. A provider filter MUST limit the model choices, the table, and both token charts to that provider. Saving a provider, account, key, or model MUST NOT change any `consumedTokens` value.

#### Scenario: Provider total
- **WHEN** the operator filters Token 消耗 to one provider
- **THEN** the displayed total equals the sum of `consumedTokens` on that provider's models, and models of other providers are absent

#### Scenario: Single model
- **WHEN** the operator then selects one model
- **THEN** the table and the model chart contain only that model, and the total equals that model's `consumedTokens`

#### Scenario: Configuration save leaves usage unchanged
- **WHEN** the operator updates a model's invocation settings
- **THEN** that model's `consumedTokens` in the following model list is unchanged by the save

### Requirement: Console uses a dark technical theme
Every view, including the token prompt, MUST use a dark page background and light primary text. Navigation among the six views MUST stay visible after the operator enters. Form controls on those views MUST remain readable on the dark background. The console MUST NOT present a light-background component theme as the primary surface.

#### Scenario: Token prompt is dark
- **WHEN** an operator opens `/admin/` before entering a token
- **THEN** the prompt is shown on a dark background with light text

#### Scenario: Authenticated views stay dark
- **WHEN** the operator enters a valid token and switches among all six views
- **THEN** each view keeps a dark background, light primary text, and a visible navigation control
