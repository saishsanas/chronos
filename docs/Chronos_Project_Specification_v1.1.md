# Chronos — Project Specification v1.1

**Project:** Chronos — Time-Traveling State Reconstruction Engine  
**Version:** 1.1  
**Status:** Architecture/Specification Baseline — Phase 1 Ready  
**Project Owner:** Saish Sanas  
**Lead AI / Technical Lead:** ChatGPT  
**Implementation Engineer:** Antigravity  
**Critical Reviewer:** Claude  
**Research / Specialist Engineer:** Gemini  

---

## 1. Executive Summary

Chronos is a temporal state reconstruction engine designed to preserve an application's state history as immutable domain events and reconstruct the exact state of an aggregate at a specified point in Chronos's recorded timeline.

The project is intentionally designed as a serious backend/system-engineering portfolio project rather than a generic CRUD application. Its primary engineering themes are event sourcing, temporal queries, deterministic state reconstruction, optimistic concurrency, transactional event publication, asynchronous projections, snapshot-assisted replay, idempotency, event schema evolution, reliability, testing, and measured performance.

Chronos will have a reusable core-engine mindset but will be validated through a concrete reference domain: a financial-style account/ledger domain. The reference domain gives the engine meaningful invariants and observable state transitions while keeping the core temporal mechanisms separable from business-specific logic.

The system will be built incrementally. Correctness of the event-sourced core takes priority over infrastructure breadth.

---

## 2. Problem Statement

Conventional application designs often retain only current state or maintain a secondary audit log. That makes questions about historical state difficult, fragile, or dependent on reconstructing history outside the application's primary model.

Chronos treats immutable domain events as the source of truth. Every valid state transition is represented by a versioned event. Current state and historical state are derived from those events.

The core problem Chronos solves is:

> Given an aggregate and a point in Chronos's recorded timeline, reconstruct the exact state that the aggregate had at that point.

Example:

```text
10:00  AccountCreated
10:15  MoneyDeposited +₹10,000
11:30  MoneyWithdrawn -₹3,000
12:45  MoneyDeposited +₹8,000
14:10  AccountFrozen
```

A query at 11:45 should reconstruct the state immediately after the events recorded up to that point.

---

## 3. Project Vision

Chronos should demonstrate that a fresher-level engineer can design and implement a non-trivial backend system with explicit correctness guarantees and measurable behavior.

The target outcome is not maximum feature count. The target outcome is depth in four areas:

1. **True Event Sourcing** — immutable events are the source of truth.
2. **Temporal Reconstruction** — point-in-time state and state comparison are first-class capabilities.
3. **Reliability** — concurrency, idempotency, transactional publication, replay, and failure recovery are explicitly designed and tested.
4. **Performance** — snapshots, indexes, caching, and replay behavior are measured rather than claimed.

---

## 4. Project Positioning

Chronos is deliberately different from the developer's existing projects.

### Existing project profile

- **SaishTask:** conventional full-stack web development.
- **CareWave:** mobile/real-time safety system involving location, emergency workflows, alerts, and related capabilities.

### Chronos profile

Chronos emphasizes:

- Java backend engineering
- Event sourcing
- Temporal data
- Distributed event processing
- Concurrency and consistency
- State reconstruction
- Reliability
- Database engineering
- Performance engineering
- Developer-oriented tooling

Chronos must not drift into GPS, emergency, mobile safety, or tracking functionality.

---

## 5. Reference Domain

### 5.1 Selected reference domain

Chronos will use a **financial-style account/ledger domain** as its concrete reference implementation.

This is not intended to be a real banking product. It is a controlled domain for demonstrating event-sourced state transitions and historical reconstruction.

### 5.2 Domain characteristics

A reference account may include state such as:

- account status
- balance
- credit/transaction limit
- version
- other explicitly defined domain attributes

Potential business events include:

- AccountCreated
- MoneyDeposited
- MoneyWithdrawn
- AccountFrozen
- AccountUnfrozen
- LimitChanged
- CorrectionIssued

The final event set will be determined during Phase 1 domain modelling.

### 5.3 Generic Core vs. Reference Domain — Decision

Chronos v1 will contain a **real reusable core at the code level**, but only one concrete reference domain will be implemented initially.

The reusable core owns concepts such as:

- `Event` / event envelope
- `EventStore`
- `EventStream`
- `AggregateId` / aggregate type
- sequence/version handling
- snapshot handling
- replay orchestration
- temporal query mechanics
- concurrency checks

The Account domain owns:

- Account state
- commands
- invariants
- domain events
- reducer/state transition logic

The project will **not** attempt to support arbitrary domains through a reflection-heavy or configuration-heavy framework. The generic core must be demonstrated through clean interfaces and one real domain implementation.

---

## 6. Scope

### 6.1 In scope

- Single-aggregate temporal reconstruction
- Immutable event store
- Versioned events
- Aggregate sequence numbers
- Recorded/commit-time temporal semantics
- Event replay
- Snapshot-assisted replay
- Historical state queries
- State timeline
- State diff between two points in time
- Optimistic concurrency control
- Command idempotency
- Consumer/event-processing idempotency
- Transactional outbox
- Kafka-based asynchronous event propagation
- Read-model projections
- Projection rebuild/replay
- Event schema evolution/upcasting
- Compensating events for corrections
- Failure and recovery testing
- Performance benchmarking
- Authentication and role-based access control
- Audit of administrative/user actions where distinct from domain events
- Dockerized local environment
- Automated tests
- CI/CD
- Observability

### 6.2 Out of scope for v1

- Full bitemporal database semantics
- Cross-aggregate dynamic point-in-time reconstruction over the entire system
- Production-grade multi-region deployment
- Kubernetes orchestration
- Real payment integrations
- Real banking integrations
- Full GDPR compliance infrastructure
- A generalized framework supporting every possible domain without a reference implementation
- Unnecessary microservices introduced only for architectural appearance

Cross-aggregate historical analytics may later be implemented as asynchronous projections rather than direct dynamic replay.

---

## 7. Temporal Semantics

### 7.1 Definition of time

Chronos v1 defines historical truth using **Chronos recorded/commit time** rather than trusting client-provided occurrence timestamps.

Each event will contain a durable `recordedAt` timestamp assigned as part of the persistence process.

### 7.2 Deterministic ordering

Each aggregate has a strictly monotonic `sequenceNumber`.

Example:

```text
Account A
  sequence 1
  sequence 2
  sequence 3
  sequence 4
```

This provides deterministic ordering within an aggregate and is also the primary optimistic-concurrency boundary.

### 7.3 Query meaning

A query such as:

```text
GET state of Account A at 2026-09-15T14:32:18
```

means:

> Reconstruct Account A using events recorded by Chronos with `recordedAt <= T`.

The timestamp comparison is **inclusive**. If several events have equal `recordedAt`, `sequenceNumber ASC` determines their order within the aggregate.

### 7.4 Boundary behavior

- **Timestamp before aggregate creation:** return a typed `AGGREGATE_NOT_FOUND_AT_TIME` response; the aggregate did not exist at that time.
- **Timestamp after the latest recorded event:** return the latest currently recorded state. No hypothetical future state is inferred. The response should identify the latest recorded event/version used for reconstruction.
- **Timestamp exactly equal to an event's `recordedAt`:** include that event and all earlier events recorded for the aggregate.

### 7.5 Authoritative historical-read path

The **event store is the authoritative source for point-in-time historical queries**. Historical reconstruction and temporal diff correctness do not depend on Kafka consumer freshness.

Asynchronous projections are optimized read models for current-state queries, search, analytics, and operational tooling. They must not silently replace the authoritative historical reconstruction path in v1.

Therefore, Kafka/projector lag cannot make an authoritative historical query return stale history. Any explicitly eventual-consistency projection endpoint must be labeled and documented as such.

---

## 8. Core Architectural Principle — Event Sourcing

The event store is the source of truth.

Chronos must not implement the following anti-pattern:

```text
Mutable account state
       +
Optional audit table
```

Instead:

```text
Command
  ↓
Validate invariant
  ↓
Create event
  ↓
Append immutable event
  ↓
Derive state/projections
```

The current state is derived from event history.

Historical state is reconstructed by applying the relevant events to an initial state or to a valid snapshot.

---

## 9. Core Domain Model

Phase 1 will formally define aggregates, commands, events, invariants, and state transitions.

The expected conceptual model is:

```text
Aggregate
   │
   ├── receives Command
   │
   ├── validates current state/invariants
   │
   └── emits Domain Event(s)
                    │
                    ▼
              Event Store
                    │
                    ▼
              State Folding
```

The reducer/fold model should remain side-effect free:

```java
State reduce(State current, Event event)
```

Replay must not send notifications, call external services, or produce unrelated side effects.

### 9.1 Monetary Representation

The reference Account domain will use **integer minor units** for monetary values. For the v1 INR reference implementation:

- `100 paise` is represented as `100`
- Java type: `long` for persisted/calculated minor units
- currency: explicit `INR` code in the domain model
- no `float`/`double` will be used for monetary calculations

This keeps reducer arithmetic deterministic and avoids floating-point rounding errors.

---

## 10. Event Envelope

The baseline envelope is:

```json
{
  "eventId": "uuid",
  "aggregateId": "uuid",
  "aggregateType": "Account",
  "sequenceNumber": 42,
  "eventType": "MoneyDeposited",
  "eventVersion": 1,
  "recordedAt": "2026-09-15T14:32:18Z",
  "metadata": {
    "correlationId": "uuid",
    "causationId": "uuid",
    "actorId": "string",
    "idempotencyKey": "string"
  },
  "payload": {}
}
```

---

## 11. Event Store

### 11.1 Technology

**PostgreSQL 16** with `JSONB` for event payloads.

### 11.2 Key Constraints

Uniqueness constraint on `(aggregate_id, sequence_number)`.

---

## 12. Optimistic Concurrency Control

Every write specifies expected version $V_{\text{expected}}$. If $V_{\text{current}} \neq V_{\text{expected}}$, throw concurrency conflict (`409 Conflict`).

---

## 13. Command Idempotency

Database-enforced unique constraint on `(actor_id, idempotency_key)`. Reused key with different payload returns conflict.

---

## 14. Transactional Outbox Pattern

Atomic commit of `events` table write and `outbox` table record within a single PostgreSQL transaction. Poller reads via `SELECT ... FOR UPDATE SKIP LOCKED` and publishes to Kafka.

---

## 15. Testing & Verification

JUnit 5 + Testcontainers for real PostgreSQL, Kafka, and Redis instances.

---

## 16. Phase Roadmap

- **Phase 0**: Specification & Architectural Baseline (COMPLETE - Specification v1.1 Frozen)
- **Phase 1**: Domain & Event Model Design (IN PROGRESS)
- **Phase 2**: Event Store Core & Persistence Layer
- **Phase 3**: Temporal State Reconstruction Engine & Snapshots
- **Phase 4**: Event Publication & Outbox / Kafka Projections
- **Phase 5**: Reliability, Concurrency & Idempotency Testing Suite
- **Phase 6**: Chronos Visual Timeline & Developer UI
- **Phase 7**: Performance Benchmarks & Observability
- **Phase 8**: Productionization, Dockerization & CI/CD
- **Phase 9**: Portfolio Packaging & Interview Defense
