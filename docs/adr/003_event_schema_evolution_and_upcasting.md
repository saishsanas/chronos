# ADR 003: Event Schema Evolution and Deterministic In-Memory Upcasting

## Status
Approved

## Context
Event-sourced systems rely on the immutability of the event log: events represent facts that occurred in the past and cannot be rewritten or mutated in place. As business domains expand and regulatory requirements evolve, event schemas inevitably change (new fields, enriched data, restructured payloads).

Rewriting historical events directly in the database (`UPDATE event_store SET ...`) violates event immutability, destroys audit trails, risks data corruption, and invalidates cryptographic or integrity guarantees. Conversely, scattering schema-version branching throughout domain models and aggregate reducers (`if (version == 1) ... else if (version == 2) ...`) pollutes domain logic, increases cyclomatic complexity, and makes business rules brittle and unmaintainable.

## Decision
We implement a deterministic, in-memory event schema evolution and upcasting architecture adhering to the canonical replay pipeline:

```
Stored Event v1 (PostgreSQL event_store)
      ↓
EventSchemaRegistry (inspect stored eventVersion & resolve eventType)
      ↓
EventUpcasterChain (deterministic v1 → v2 transformation in memory)
      ↓
Current Canonical Event v2
      ↓
AccountReducer (pure domain reduction on canonical representation)
      ↓
Reconstructed Aggregate State / Projection / Snapshot
```

### 1. Authoritative Immutability of Historical Events
The PostgreSQL `event_store` table is the authoritative historical source of truth. Raw database rows are never modified to adapt to application schema changes. Historical rows retain their original `event_id`, `aggregate_id`, `aggregate_type`, `sequence_number`, `event_type`, `event_version`, `recorded_at`, `metadata`, and raw `payload`.

### 2. Selected Real Event Evolution: `MoneyDeposited` (v1 → v2)
To provide real-world financial provenance tracking (identifying whether a deposit was manual teller input, an automated wire transfer, a payment gateway, or an ATM transaction), the `MoneyDeposited` event evolves from **v1** to **v2**:

- **Legacy v1 Payload**:
  ```json
  {
    "amountMinor": 200000,
    "currency": "INR",
    "resultingBalanceMinor": 200000
  }
  ```
- **Canonical v2 Payload**:
  ```json
  {
    "amountMinor": 200000,
    "currency": "INR",
    "resultingBalanceMinor": 200000,
    "source": "MANUAL"
  }
  ```
- **Deterministic Transformation**:
  When replaying historical v1 `MoneyDeposited` events, `MoneyDepositedV1ToV2Upcaster` deterministically injects `"source": "MANUAL"` into the payload and sets `eventVersion = 2` on the in-memory envelope. The raw row in `event_store` remains version 1.
- **New Writes**:
  All new `DepositMoney` commands write canonical v2 events with `eventVersion = 2` and `"source"` populated directly across the Event Store, Transactional Outbox, and Kafka.

### 3. Version Chaining (`EventUpcasterRegistry`)
Upcasting is orchestrated through a version chain:
$$v_1 \to v_2 \to \dots \to v_N$$
Rather than point-to-point mappings from every historical version to the newest version, upcasters define atomic step transitions (`sourceVersion` to `targetVersion = sourceVersion + 1`). If a version 1 event is replayed in a system running version 3, the registry invokes `v1 → v2`, then `v2 → v3`.

### 4. Canonical Current Representation in Domain Reducers
`AccountReducer` enforces that only canonical current versions (e.g. `MoneyDeposited` v2, other account events v1) are reduced into `AccountState`. No legacy version branching exists inside `AccountReducer`. If a non-canonical event reaches the reducer, it fails fast with `UnsupportedEventVersionException`.

### 5. Strict Rejection of Future Versions and Poison Events
If an event arrives with `eventVersion > currentVersion` or with an unknown `eventType`, the system:
- In the Replay Pipeline: Throws typed `UnsupportedEventVersionException` or `UnknownEventTypeException` (extending `CorruptedEventStreamException`), preventing corrupted aggregate reconstruction.
- In the Kafka/Inbox Pipeline: Quarantines the event in `inbox_events` as poison (`QUARANTINED`), increments `chronos.inbox.poison` and `chronos.event.unsupported_version`, and acknowledges the offset to prevent consumer head-of-line blocking.

### 6. Snapshot and CQRS Projection Interaction
- **Snapshots**: Snapshots capture the canonical `AccountState` at a given sequence number. Replaying from a snapshot applies upcasters to any subsequent stream events ($seq > seq_{snapshot}$). If snapshot metadata or replay hash indicates incompatibility (`isValid() == false`), the snapshot is rejected and the system falls back cleanly to full event replay from sequence 1.
- **Projections & Rebuilds**: The `AccountProjectionHandler` and `ProjectionRebuildService` upcast incoming stream envelopes before passing them to the reducer, guaranteeing that semantic-equivalent v1 and v2 events yield identical projection read models.

## Observability
We introduce low-cardinality Micrometer metrics:
- `chronos.event.upcast`: Counter of successfully upcasted events, tagged by `eventType`, `fromVersion`, and `toVersion`.
- `chronos.event.upcast.failure`: Counter of failed upcasts, tagged by `eventType`.
- `chronos.event.unsupported_version`: Counter of rejected future or unsupported versions, tagged by `eventType` and `version`.
- `chronos.event.unknown_type`: Counter of unrecognized event types, tagged by `eventType`.

## Consequences

### Positive
- **Guaranteed Immutability**: Historical PostgreSQL records are never updated or rewritten.
- **Clean Domain Logic**: Zero legacy version checks or branching inside `AccountReducer`.
- **Determinism**: Upcasters are pure functions with no database, network, clock, or state dependencies.
- **Mixed Stream Resilience**: Streams containing both legacy v1 and modern v2 events replay with 100% mathematical and business accuracy.
- **Safe Outbox/Kafka Consistency**: New events cleanly propagate version 2 through outbox, message headers, and inbox.

### Trade-offs & Limitations
- **In-Memory Transformation Overhead**: Reading legacy events requires deserializing the raw JSON payload, creating an updated in-memory `DomainEventEnvelope`, and applying the upcaster before reduction.
- **One Real Evolution in Wave 2**: Wave 2 implements the evolution of `MoneyDeposited` (v1 → v2). Other events currently remain on version 1 until domain requirements warrant their evolution.
