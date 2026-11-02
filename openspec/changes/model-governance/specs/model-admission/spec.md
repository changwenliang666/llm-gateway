## Purpose

按模型控制账号是否轮询、每分钟能通过多少请求和 Token，并在模型持续调用失败时暂时拒绝新请求。这些规则与供应商协议无关，后续 Google、字节或其他适配器共用同一条准入链。

## ADDED Requirements

### Requirement: Load balance is a per-model switch
The system MUST persist a load-balance switch on each model. When the switch is on, successive requests MUST rotate eligible accounts in ascending account id order using the existing Redis cursor. When the switch is off, every new request MUST select the eligible account with the smallest account id and MUST NOT advance the account cursor. Key rotation inside the selected account MUST still advance. The existing single retry to the next account on upstream 401, 429, or 5xx, when the response has not been committed, MUST remain.

#### Scenario: Rotation when enabled
- **WHEN** a model has load balance enabled and at least two eligible accounts
- **THEN** successive new requests select those accounts in ascending account id order

#### Scenario: Pinned account when disabled
- **WHEN** a model has load balance disabled and more than one eligible account
- **THEN** each new request selects the smallest account id and the account cursor does not change

### Requirement: Enabling load balance requires two schedulable accounts
The system MUST reject turning load balance on when fewer than two accounts are schedulable for that model. A schedulable account MUST be linked to the model, enabled, owned by the model's provider, and holding at least one enabled key. Disabling an account MUST remove it from later selections without a restart. An already-enabled switch MUST NOT be cleared automatically when the pool later shrinks to one account; that request MUST use the remaining account.

#### Scenario: Reject enable
- **WHEN** an operator enables load balance for a model with fewer than two schedulable accounts
- **THEN** the system rejects the change and leaves the stored switch unchanged

#### Scenario: Empty pool
- **WHEN** a model has no schedulable account
- **THEN** the system returns HTTP 503 and does not call an upstream

### Requirement: Model rate limits are independent
The system MUST persist a rate-limit switch, RPM, and TPM on each model. When the switch is off, requests MUST NOT consume that model's counters. When it is on, the system MUST reject a request before account selection if admitting it would exceed a configured RPM or TPM in the current one-minute window. RPM and TPM MUST be enforced atomically together. A rejection MUST be HTTP 429 with error type `rate_limit_error` and MUST NOT select another account. One model's counters MUST NOT affect another model. Enabling the switch MUST require at least one of RPM or TPM to be a positive integer.

#### Scenario: RPM on one model
- **WHEN** model A has reached its RPM and model B has not
- **THEN** a new request for A is rejected with HTTP 429 and a request for B is still admitted

#### Scenario: TPM rejection
- **WHEN** admitting the estimated tokens would exceed the model's TPM
- **THEN** the request is rejected with HTTP 429 and the upstream is not called

#### Scenario: Rate limit is not a model failure
- **WHEN** a request is rejected by the model rate limit
- **THEN** the model circuit breaker failure count does not increase

### Requirement: Model circuit breaker uses three shared states
Each model MUST have its own circuit state shared across instances. CLOSED MUST allow requests. After a configured number of consecutive call failures the state MUST become OPEN and new requests MUST be rejected with HTTP 503 and message 「模型暂时不可用」 without selecting an account. After the configured cool-down, the next request MUST move the state to HALF_OPEN and allow at most the configured number of probe requests. A probe success MUST return the state to CLOSED and clear the failure count. A probe failure MUST return the state to OPEN and restart the cool-down. Extra probes beyond the limit MUST be rejected. Call failures MUST be connection failures, timeouts, and upstream 5xx. Upstream 4xx, gateway rate-limit rejections, and routing-infrastructure errors MUST NOT be recorded as call failures. A successful 2xx or 3xx MUST clear the consecutive failure count.

#### Scenario: Open after consecutive failures
- **WHEN** a model accumulates the configured number of consecutive upstream 5xx or transport failures
- **THEN** the next request is rejected with HTTP 503 and the upstream is not called

#### Scenario: Half-open after cool-down
- **WHEN** the cool-down has elapsed since the model entered OPEN
- **THEN** the next request is allowed through as a probe

#### Scenario: Probe success closes
- **WHEN** a HALF_OPEN probe receives a successful upstream response
- **THEN** later requests are admitted as CLOSED

#### Scenario: Probe failure reopens
- **WHEN** a HALF_OPEN probe fails with an upstream 5xx or transport error
- **THEN** later requests are rejected as OPEN until a new cool-down elapses

#### Scenario: Probe slots are limited
- **WHEN** several requests arrive while the model is HALF_OPEN and the probe limit is one
- **THEN** only one request is forwarded upstream

### Requirement: Streaming still does not retry after output starts
Once the gateway has started writing a stream to the caller, it MUST NOT select another account or send the request again. A stream that has started MUST be recorded as a circuit success.

#### Scenario: Stream already committed
- **WHEN** the upstream stream has written the first byte to the caller
- **THEN** the gateway does not open a second upstream request for that call
