# 02. Payment Processing Flows

Date: 2026-05-29

This document defines the core payment flows, state transitions, idempotency behavior, processor simulation behavior, ledger effects, events, and failure modes.

For implementation decisions, follow `docs/IMPLEMENTATION_MASTER_PLAN.md`.

## 1. Payment Lifecycle Overview

```text
Customer checkout
  -> merchant creates customer
  -> merchant creates payment method
  -> merchant creates PaymentIntent
  -> merchant confirms PaymentIntent
  -> platform creates PaymentAttempt
  -> platform runs fraud decision
  -> platform may block, request 3DS, send to review, or authorize
  -> processor simulator returns outcome
  -> platform captures automatically or waits for manual capture
  -> merchant may refund
  -> customer may dispute
  -> ledger and events record all money movement
```

## 2. PaymentIntent State Machine

V1 statuses:

- `REQUIRES_PAYMENT_METHOD`
- `REQUIRES_CONFIRMATION`
- `REQUIRES_FRAUD_CHECK`
- `REQUIRES_ACTION`
- `PROCESSING`
- `AUTHORIZED`
- `REQUIRES_CAPTURE`
- `CAPTURED`
- `CANCELED`
- `REFUNDED`
- `DISPUTED`
- `FAILED`

State diagram:

```text
REQUIRES_PAYMENT_METHOD
  -> REQUIRES_CONFIRMATION

REQUIRES_CONFIRMATION
  -> REQUIRES_FRAUD_CHECK

REQUIRES_FRAUD_CHECK
  -> FAILED                 when fraud action is BLOCK
  -> REQUIRES_ACTION        when fraud action is REQUEST_3DS
  -> PROCESSING             when fraud action is ALLOW
  -> PROCESSING             when review later approves

REQUIRES_ACTION
  -> REQUIRES_FRAUD_CHECK   after successful 3DS completion
  -> FAILED                 after failed 3DS
  -> CANCELED               if canceled

PROCESSING
  -> AUTHORIZED             after auth succeeds and manual capture
  -> CAPTURED               after auth succeeds and automatic capture
  -> FAILED                 after processor decline

AUTHORIZED
  -> CAPTURED               after capture
  -> CANCELED               after cancel/void

CAPTURED
  -> REFUNDED               after full refund
  -> DISPUTED               after chargeback

FAILED, CANCELED, REFUNDED, DISPUTED are terminal for V1
```

Partial refunds may keep status as `CAPTURED` with refund records. Full refund moves to `REFUNDED`.

## 3. PaymentAttempt Lifecycle

PaymentAttempt is created on confirmation.

PaymentAttempt owns:

- the concrete attempt number
- payment method used
- idempotency key
- fraud decision
- processor authorization result
- 3DS status

Attempt state examples:

- `CREATED`
- `FRAUD_CHECKED`
- `BLOCKED`
- `REQUIRES_3DS`
- `IN_REVIEW`
- `AUTHORIZED`
- `DECLINED`
- `FAILED`

Implementation can start with a smaller enum as long as PaymentIntent states remain correct and audit fields are preserved.

## 4. Create Customer Flow

```text
POST /customers
  -> resolve MerchantContext from X-Api-Key
  -> normalize email/phone
  -> hash email/phone where stored as identity signals
  -> create Customer
  -> audit customer.created
  -> return Customer
```

Security:

- Do not store unnecessary raw PII in V1.
- Prefer hashes for fraud linking fields.

## 5. Create Payment Method Flow

```text
POST /payment_methods
  -> resolve MerchantContext from X-Api-Key
  -> validate customer belongs to merchant
  -> accept test card details
  -> derive token
  -> derive fingerprint
  -> derive BIN / last4 / country / funding
  -> discard raw PAN
  -> discard CVV
  -> create PaymentMethod
  -> audit payment_method.created
  -> return masked PaymentMethod
```

Important:

- Raw PAN and CVV may exist only in request processing memory.
- They must not be logged.
- They must not be persisted.
- Test card number drives deterministic simulator behavior.

## 6. Create PaymentIntent Flow

```text
POST /payment_intents
  -> resolve MerchantContext from X-Api-Key
  -> require idempotency key
  -> validate merchant is active
  -> validate customer belongs to merchant
  -> validate amount and currency
  -> validate capture method
  -> create PaymentIntent
  -> write outbox payment_intent.created
  -> create webhook delivery records
  -> store idempotency response
  -> return PaymentIntent
```

Initial status:

- `REQUIRES_PAYMENT_METHOD` if no payment method is attached or provided
- `REQUIRES_CONFIRMATION` if payment method is ready

Validation:

- amount must be positive
- currency must be supported
- merchant must be active
- customer must belong to merchant
- payment method must belong to customer

## 7. Confirm PaymentIntent Flow

```text
POST /payment_intents/{id}/confirm
  -> resolve MerchantContext from X-Api-Key
  -> require idempotency key
  -> load or reserve merchant-scoped idempotency record
  -> load PaymentIntent by id + merchant with lock
  -> validate status is confirmable
  -> create PaymentAttempt
  -> set intent to REQUIRES_FRAUD_CHECK
  -> extract features
  -> read velocity counters
  -> evaluate rules
  -> call model service, later
  -> persist FraudDecision
  -> persist FeatureSnapshot
  -> persist RuleExecutionResult rows
  -> apply fraud action
```

Fraud action branches:

```text
BLOCK
  -> mark attempt BLOCKED
  -> mark intent FAILED
  -> write fraud_decision.created event
  -> return blocked response

REQUEST_3DS
  -> mark attempt REQUIRES_3DS
  -> mark intent REQUIRES_ACTION
  -> write fraud_decision.created event
  -> return next_action payload

REVIEW
  -> mark attempt IN_REVIEW
  -> create ManualReviewCase
  -> write fraud_decision.created and review.opened events
  -> return review response

ALLOW
  -> call processor simulator
```

Processor branches after `ALLOW`:

```text
processor approval + automatic capture
  -> create Authorization
  -> create Capture
  -> write ledger entries
  -> mark intent CAPTURED
  -> emit authorization.created and capture.created

processor approval + manual capture
  -> create Authorization
  -> mark intent REQUIRES_CAPTURE or AUTHORIZED
  -> emit authorization.created

processor decline
  -> create Authorization/ProcessorEvent with decline response
  -> mark attempt DECLINED
  -> mark intent FAILED
  -> emit authorization.created or payment_attempt.failed
```

## 8. 3DS Flow

V1 simulates 3DS. It does not perform real browser/device challenge flows.

```text
confirm
  -> fraud action REQUEST_3DS
  -> intent REQUIRES_ACTION
  -> response includes next_action simulation token

complete 3DS, implementation endpoint can be added when needed
  -> simulator returns 3DS success or failure
  -> on success, return to fraud check or proceed to processor
  -> on failure, mark intent FAILED
```

Initial implementation may represent 3DS completion through deterministic test cards or a simple internal endpoint.

Rules:

- `REQUEST_3DS` does not mean capture.
- Successful 3DS does not bypass audit.
- Failed 3DS prevents authorization.

## 9. Manual Review Flow

Manual review starts when fraud action is `REVIEW`.

```text
fraud decision REVIEW
  -> create ManualReviewCase
  -> mark attempt IN_REVIEW
  -> emit review.opened
```

Analyst actions:

```text
approve
  -> continue authorization or allow capture according to payment state
  -> emit review.closed

cancel
  -> mark intent CANCELED or FAILED depending on state
  -> emit review.closed

refund
  -> create Refund if payment was captured
  -> write ledger entries
  -> emit refund.created and review.closed

refund_and_report_fraud
  -> create Refund if captured
  -> create FraudLabel
  -> optionally add blocklist item
  -> emit refund.created and review.closed
```

Auth/capture behavior:

- If the payment is manual capture, approving review must not automatically capture unless configured.
- If payment was not authorized yet, approval allows authorization to continue.
- If payment was already captured, review outcomes may lead to refund or fraud label.

## 10. Capture Flow

```text
POST /payment_intents/{id}/capture
  -> require idempotency key
  -> load PaymentIntent with lock
  -> validate status is AUTHORIZED or REQUIRES_CAPTURE
  -> validate capture amount <= remaining authorized amount
  -> create Capture
  -> write balanced ledger entries
  -> update PaymentIntent status
  -> emit capture.created
  -> create webhook deliveries
  -> return Capture/PaymentIntent
```

Invalid:

- capture before authorization
- capture after cancel
- capture after failed authorization
- capture after full capture
- over-capture
- duplicate capture without idempotency replay

## 11. Cancel / Void Flow

```text
POST /payment_intents/{id}/cancel
  -> require idempotency key
  -> load PaymentIntent with lock
  -> validate cancelable state
  -> void authorization if one exists and was not captured
  -> mark intent CANCELED
  -> emit payment_intent.canceled
  -> create webhook deliveries
  -> return PaymentIntent
```

Cancelable states:

- `REQUIRES_PAYMENT_METHOD`
- `REQUIRES_CONFIRMATION`
- `REQUIRES_ACTION`
- `AUTHORIZED`
- `REQUIRES_CAPTURE`

Not cancelable:

- `CAPTURED`
- `REFUNDED`
- `DISPUTED`
- `FAILED`

Captured payments require refund, not cancel.

## 12. Refund Flow

```text
POST /refunds
  -> require idempotency key
  -> lock tenant-scoped PaymentIntent
  -> validate approved authorization and balanced capture ledger evidence
  -> validate full refundable amount
  -> create Refund
  -> write exact balanced capture-reversal ledger entries
  -> update PaymentIntent to REFUNDED
  -> emit refund.created
  -> create webhook deliveries
  -> return Refund

GET /refunds/{id}
  -> scope by authenticated merchant
  -> return durable Refund lineage
```

Invalid:

- refund before capture
- refund canceled payment
- refund when capture ledger evidence is missing or inconsistent
- partial, over, or second refund
- refund failed payment
- over-refund
- duplicate refund on retry

Partial refund:

- capture remains partially refunded
- PaymentIntent can remain `CAPTURED`

Full refund:

- PaymentIntent moves to `REFUNDED`

## 13. Chargeback / Dispute Flow

```text
POST /chargebacks
  -> validate captured payment
  -> create Chargeback
  -> write dispute ledger entries
  -> create FraudLabel if reason indicates fraud
  -> create or link DisputeEvidencePacket
  -> mark PaymentIntent DISPUTED
  -> emit chargeback.created
  -> create webhook deliveries
  -> return Chargeback
```

Chargeback reasons:

- fraudulent
- product_not_received
- duplicate
- credit_not_processed
- general

Fraud-label behavior:

- fraudulent chargeback creates `FraudLabel`
- non-fraud chargeback may create a dispute record but not a fraud label
- friendly-fraud and policy-abuse cases require explicit fraud type, source,
  and confidence instead of blindly treating every dispute as stolen-card fraud

Dispute evidence packet:

- links the chargeback to authorization, fraud decision, 3DS, AVS/CVC,
  fulfillment, refund, prior undisputed transaction, and risk-context evidence
- preserves reason code, due date, submission status, and outcome
- must not mutate the original real-time FraudDecision

## 14. Idempotency Flow

All mutating payment APIs require an idempotency key.

```text
request arrives
  -> resolve MerchantContext from X-Api-Key
  -> compute request hash from method + path + normalized body
  -> look up merchant + key
  -> if no key exists, reserve key as IN_PROGRESS
  -> if same key and same hash completed, return stored response
  -> if same key and different hash, reject
  -> execute operation
  -> store status + response body
```

Failure cases:

- If operation fails before durable state change, key can be retried.
- If operation succeeds but response fails, retry returns stored response.
- If a key is stuck `IN_PROGRESS`, provide timeout/expiry handling.

## 15. Webhook Flow

For the learning-first explanation of why webhooks exist, see [Webhooks From First Principles](../learning/concepts/WEBHOOKS.md).

Chapter 19 separates three kinds of truth:

```text
WebhookEvent           immutable fact and exact payload bytes
  -> WebhookDelivery   routing and aggregate state for one endpoint
    -> WebhookDeliveryAttempt
                       append-only evidence for one HTTP attempt
```

The Phase 2 flow is:

```text
payment mutation commits its domain state
  -> immutable WebhookEvent written in the same transaction
  -> matching webhook endpoints selected under shared endpoint locks
  -> WebhookDelivery rows created
  -> bounded worker claims due delivery and commits claim
  -> worker signs exact persisted payload bytes
  -> worker marks dispatch start and renews the claim atomically
  -> worker performs HTTP outside database transaction
  -> append-only attempt result and aggregate delivery state committed
```

Phase 5 introduces the general domain outbox and Kafka publication. Chapter 19
does not pretend the webhook event envelope is already that cross-consumer
outbox.

V1 webhook payload should include:

- event ID
- event type
- created timestamp
- API version
- object ID
- object type
- merchant ID

Delivery statuses:

- `PENDING`
- `DELIVERING`
- `RETRY_SCHEDULED`
- `SUCCEEDED`
- `EXHAUSTED`

Delivery is at least once. If the worker sends successfully and crashes before
recording success, lease recovery may send the same immutable event again. The
event ID remains stable. Merchants verify the timestamped body signature first,
parse the signed body `id`, reject any mismatch with the advisory
`THAAP-Event-Id` header, and deduplicate on the authenticated body value.

The worker claims each attempt immediately before dispatch; rows do not wait
inside a pre-leased serial batch. DNS has its own bounded resolver pool, queue,
and deadline. Transient DNS outcomes retry as known no-send; only explicit
target-policy denial is terminal. Connect time plus the total TLS/write/read
deadline must fit inside the renewed lease. The worker durably marks dispatch
start while renewing the claim: before that marker send evidence is false,
while an in-flight or
expired post-marker attempt has unknown send evidence until transport
finalization establishes truth. Unclassified runtime failures preserve the
claim for recovery instead of inventing terminal delivery truth. Expired
`DELIVERING` rows remain recovery-eligible after endpoint disable so a crashed
in-flight attempt converges to terminal state rather than becoming stranded.
Claim selection checks active endpoint eligibility in a subquery and row-locks
only delivery rows. It does not acquire an implicit endpoint write lock through
a joined `FOR UPDATE`, which would undermine the endpoint-first kill-switch
lock protocol.
Endpoint disable takes an exclusive endpoint lock. It either follows and
exhausts deliveries already routed under shared locks or precedes routing and
causes the active-endpoint recheck to create no delivery.
Retry finalization follows the same endpoint-before-delivery lock order so a
disable cannot commit between endpoint-state validation and retry scheduling.

The exact Chapter 19 signature, retry, claiming, secret-encryption, network
target, and proof contract is owned by the Chapter 19 section of the
implementation master plan.

## 16. Ledger Effects

V1 accounts:

- `CUSTOMER_RECEIVABLE`
- `PLATFORM_CLEARING`
- `MERCHANT_PAYABLE`
- `FEE_REVENUE`
- `REFUND_LIABILITY`
- `DISPUTE_LOSS`

Every money movement writes a ledger transaction group.

Capture:

```text
Debit  CUSTOMER_RECEIVABLE
Credit PLATFORM_CLEARING

Debit  PLATFORM_CLEARING
Credit MERCHANT_PAYABLE

Debit  MERCHANT_PAYABLE
Credit FEE_REVENUE
```

Refund:

```text
Debit  MERCHANT_PAYABLE
Credit CUSTOMER_RECEIVABLE
```

Chargeback:

```text
Debit  DISPUTE_LOSS or MERCHANT_PAYABLE
Credit CUSTOMER_RECEIVABLE
```

The exact accounting model can evolve, but each transaction group must balance from day one.

## 17. Processor Simulator Outcomes

Chapter 20 centralizes the implemented deterministic outcomes in one immutable
catalog keyed by safe stored BIN-plus-last4 metadata:

- approval
- issuer decline
- insufficient funds
- suspected fraud decline
- CVC failure
- AVS line1 failure
- AVS postal code failure
- 3DS required

Any other valid simulator card follows explicit default approval. 3DS success
and failure require a later challenge-completion boundary and are not represented
as if they already occurred.

Simulator response should include:

- processor
- response code
- network status
- authorized amount
- CVC result
- AVS result
- 3DS result
- reason code
- latency simulation, optional

The public fixture table lives in
[`PROCESSOR_AUTHORIZATION.md`](../learning/concepts/PROCESSOR_AUTHORIZATION.md).
A processor `SUSPECTED_FRAUD` decline is issuer/network evidence; it does not
become a THAAP FraudDecision. Phase 3 owns the first real `BLOCK`,
`REQUEST_3DS`, and `REVIEW` decisions.

## 18. Failure Modes

### 18.1 Database Failure

Behavior:

- return error
- do not produce partial state
- idempotency should not record success unless operation commits

### 18.2 Redis Failure

Behavior:

- for early phases, fail closed for velocity-dependent fraud if Redis is required
- later, support degraded rules-only behavior based on durable history where possible

### 18.3 Processor Simulator Failure

Behavior:

- mark attempt failed only if failure is definitive
- otherwise return retriable error
- idempotency retry must not duplicate authorization

### 18.4 Webhook Delivery Failure

Behavior:

- payment operation still succeeds
- delivery failure is recorded
- retries are scheduled

### 18.5 Model Service Timeout

Behavior:

- use rules-only fallback
- record `MODEL_TIMEOUT`
- include reason code in FraudDecision

## 19. Acceptance Criteria For Payment Flows

Payment flows are architecturally complete when:

- create, confirm, capture, cancel, refund, and chargeback are implemented
- every mutating API has idempotency
- raw PAN and CVV are not persisted
- every confirm creates PaymentAttempt
- every fraud MVP confirm creates FraudDecision
- `BLOCK` prevents authorization
- `REQUEST_3DS` pauses in `REQUIRES_ACTION`
- `REVIEW` creates ManualReviewCase
- `ALLOW` reaches processor simulator
- captures and refunds create balanced ledger entries
- webhook delivery rows are created for domain events
- invalid state transitions are tested
