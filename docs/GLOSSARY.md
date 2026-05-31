# THAAP Glossary

This glossary defines canonical THAAP platform terms. If a term conflicts with a reference document, follow [IMPLEMENTATION_MASTER_PLAN.md](IMPLEMENTATION_MASTER_PLAN.md).

## Payment Terms

`Merchant`
: V1 tenant root. Merchant-owned payment, fraud, webhook, ledger, review, analytics, graph, and ML data must carry or derive this tenant context.

`MerchantContext`
: Runtime tenant context resolved from merchant API credentials such as `X-Api-Key`. Merchant-facing APIs use it for authorization and query scoping instead of trusting request-body `merchantId`.

`PaymentIntent`
: Merchant-facing payment container. Tracks the desired payment amount, currency, customer, capture method, and lifecycle status. See [PaymentIntent From First Principles](learning/concepts/PAYMENT_INTENT.md).

`PaymentAttempt`
: Concrete attempt to confirm and score a PaymentIntent with a specific payment method. This is the fraud scoring, authorization, and audit unit.

`Authorization`
: Processor or issuer approval to reserve funds. Authorization does not by itself settle funds to the merchant.

`Capture`
: Operation that settles an authorized payment. Automatic capture can happen immediately after authorization; manual capture happens later.

`Cancel` / `Void`
: Operation that stops a payment before settlement. The canonical terminal status is `CANCELED`.

`Refund`
: Operation that returns funds after capture.

`Chargeback` / `Dispute`
: Post-capture customer claim. The canonical PaymentIntent status is `DISPUTED` when a dispute is active.

`ProcessorAdapter`
: Interface between payment orchestration and processor behavior. V1 uses a deterministic simulator behind this interface.

`Webhook`
: Merchant integration callback that reports payment, fraud, refund, chargeback, review, and delivery events. See [Webhooks From First Principles](learning/concepts/WEBHOOKS.md).

`Idempotency Key`
: Client-provided key that lets mutating API retries return the original response without duplicating side effects. In THAAP, idempotency keys are scoped by merchant. See [Idempotency From First Principles](learning/concepts/IDEMPOTENCY.md).

`LedgerEntry`
: Debit or credit line in the double-entry ledger.

`Transaction Group`
: Set of ledger entries for one financial event. The group is valid only when debits equal credits.

## Fraud Terms

`FraudDecision`
: Persisted decision for a PaymentAttempt, including risk score, risk level, action, reasons, model version, ruleset version, and latency.

`Fraud Action`
: One of `ALLOW`, `BLOCK`, `REQUEST_3DS`, or `REVIEW`.

`ALLOW`
: Fraud decision action that allows the payment to proceed to processor authorization.

`BLOCK`
: Fraud decision action that prevents authorization.

`REQUEST_3DS`
: Fraud decision action that requests step-up authentication and moves the PaymentIntent to `REQUIRES_ACTION`.

`REVIEW`
: Fraud decision action that opens a manual review case.

`FeatureSnapshot`
: Immutable decision-time feature JSON linked to a FraudDecision.

`FeatureDefinition`
: Metadata describing a feature name, entity type, value type, owner, version, and description.

`FeatureValue`
: Point-in-time feature value for offline joins and future model training.

`RuleExecutionResult`
: Audit record showing whether a specific rule version matched a FraudDecision and what action or reason it produced.

`ManualReviewCase`
: Analyst workflow record opened when a payment needs human review.

`FraudLabel`
: Outcome label from review, chargeback, or another trusted source. Labels may arrive long after the PaymentAttempt.

`ReplayEngine`
: Offline evaluator that replays candidate rules or models against historical PaymentAttempts and persisted FeatureSnapshots without recomputing current-state features.

## Security Terms

`Tenant Isolation`
: Guarantee that one merchant cannot read, mutate, replay, deliver webhooks for, train merchant-scoped features from, or operate on another merchant's data. See [Merchant Tenant Isolation From First Principles](learning/concepts/MERCHANT_TENANT_ISOLATION.md).

`PAN`
: Primary account number. In V1, raw PAN may appear only as transient test input and must never be persisted.

`CVV`
: Card verification value. CVV must never be persisted.

`Token`
: Safe simulated payment method reference generated from transient test card input. See [Payment Method Tokenization From First Principles](learning/concepts/PAYMENT_METHOD_TOKENIZATION.md).

`Fingerprint`
: Stable simulated card identifier used for duplicate detection and velocity features. It must not reveal raw PAN.

`PCI DSS`
: Payment Card Industry Data Security Standard. V1 is not PCI-certified, but follows PCI-shaped data minimization patterns.

## ML And Analytics Terms

`Model Serving`
: Bounded service call that returns score, risk level, model version, reason codes, and top factors. It must have timeout fallback.

`Point-In-Time Correctness`
: Rule that offline training joins may only use feature values known at or before the PaymentAttempt event time.

`Leakage`
: Use of future or unavailable information during model training or evaluation.

`PR-AUC`
: Precision-recall area under curve. Primary metric for imbalanced fraud classification.

`Calibration`
: Alignment between model scores and observed outcome rates. Calibration should happen after a model shows useful rank ordering.
