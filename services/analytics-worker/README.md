# analytics-worker

Later-phase event and replay worker.

Responsibilities:

- consume domain events
- write analytics projections
- support replay
- feed ClickHouse, later
- feed graph ingestion, later

The first implementation waits until outbox events and core payment/fraud flows are stable.
