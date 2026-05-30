# Architecture Documents

Date: 2026-05-29

These documents provide the detailed system architecture for THAAP, the payment processing and fraud detection platform.

Authoritative rule:

- `docs/IMPLEMENTATION_MASTER_PLAN.md` remains the source of truth for implementation decisions.
- These architecture documents explain system structure, flows, boundaries, invariants, and operational design.
- If architecture docs and the master plan disagree, update the architecture docs or master plan explicitly before implementation.

## Reading Order

1. [System Context And Components](./01_SYSTEM_CONTEXT_AND_COMPONENTS.md)
2. [Payment Processing Flows](./02_PAYMENT_PROCESSING_FLOWS.md)
3. [Fraud Decisioning And ML Architecture](./03_FRAUD_DECISIONING_AND_ML_ARCHITECTURE.md)
4. [Data, Events, Ledger, And Storage Architecture](./04_DATA_EVENTS_LEDGER_AND_STORAGE_ARCHITECTURE.md)
5. [Security, Observability, And Operations Architecture](./05_SECURITY_OBSERVABILITY_AND_OPERATIONS_ARCHITECTURE.md)

## Architecture Principles

- Payment processing is first-class.
- Fraud decisions are embedded in payment confirmation.
- The real-time path is deterministic and bounded.
- GenAI is offline/async only.
- Every important state transition is auditable.
- Every money movement is ledgered.
- Raw PAN and CVV are never persisted.
- Start as a modular monolith and split only when operationally justified.
- PCI DSS scope boundaries shape the simulator even though V1 is not PCI-certified.
- Synthetic data and offline replay are critical-path architecture, not later polish.
- Model thresholds are business controls and must be calibrated/backtested.
