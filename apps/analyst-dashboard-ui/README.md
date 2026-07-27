# THAAP Risk And Developer Cockpit

The Chapter 37 cockpit is a thin React/TypeScript client over the typed analyst
workspace APIs. It keeps the risk-operator credential and protected note drafts
in React memory only: the implementation does not use `localStorage`,
`sessionStorage`, IndexedDB, URLs, or analytics sinks.

The Chapter 41 developer panel adds the public simulator-only test-card
catalogue and cooperative trusted-scenario runner. The merchant API key and
separate scenario-runner credential are password inputs held only in React
memory. Every server-issued step is constrained to the same-origin public
customer/payment API catalogue, uses an explicit idempotency key, rejects HTTP
redirects, and is committed as progress only after the backend verifies the
tenant, resource graph, expected processor outcome, and synthetic lineage.

The Chapter 42 operations panel loads a bounded dead-letter view only when a
risk manager asks for it. Managers can refresh exhausted delivery evidence and
request an exact-event redelivery with a closed recovery reason.
The UI renders durable generation and attempt state, refreshes from the server
after mutation, and never displays or persists the merchant key, operator
credential, webhook secret, endpoint URL, event payload, signature, challenge,
or response bytes. The developer entry surface can also start an endpoint
verification challenge using merchant authority without requiring or sending
the scenario-runner credential.

Chapter 43R adds server-derived role projection, exact merchant monitoring
summary/cohort tables, explicit partial/empty/loading/denied/stale/dependency
states, and retry closures that retain one in-memory idempotency key across an
ambiguous retry. TanStack Query owns only bounded non-secret session/monitoring
server state; disconnect clears its cache. Handwritten CSS and evidence tables
remain deliberate: Phase 4.5 has no charting promise that justifies Tailwind or
Recharts dependency and accessibility cost.

```bash
npm install
npm test
npm run build
npm run dev
```

The development server listens on `127.0.0.1:4173` and proxies the `/risk`,
`/developer`, `/customers`, `/payment_methods`, and `/payment_intents` public
API paths to the payments API on `127.0.0.1:8080`.

Chapter 43R passes 13 Vitest tests, the TypeScript/Vite production build, the
retained-stack live browser boundary pass, and the aggregate Chapter 36–43 plus
43R closure gate. Phase 4.5 is complete. This remains a local/test-mode cockpit,
not hosted identity or a broad production dashboard claim.
