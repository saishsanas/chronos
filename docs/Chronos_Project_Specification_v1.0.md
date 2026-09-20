# Chronos: Time-Traveling State Reconstruction Engine
## Technical Specification Document v1.0
- **Status:** Architecture Draft (Phase 0 Baseline)
- **Project Lead / Author:** Saish Sanas
- **Development Approach:** AI-assisted engineering
- **Engineering Note:** Architecture, specifications, and implementation were developed by Saish Sanas using AI-assisted software engineering workflows.

---

## 1. Problem Statement & Motivation
Traditional relational and document databases rely heavily on **in-place mutation** (`UPDATE table SET balance = x WHERE id = y`). While suitable for simple CRUD paradigms, in-place updates discard historical telemetry, making point-in-time state querying impossible without complex audit tables or database-level CDC/temporal table extensions.

**Chronos** addresses this by adopting **Event Sourcing** and **Temporal State Reconstruction**:
1. All domain state transitions are captured as an **append-only, immutable event log**.
2. Current state is treated as a derivative projection computed by folding events in chronological sequence.
3. Historical entity state at any past arbitrary timestamp $T_{\text{past}}$ or event version $V_{\text{target}}$ is dynamically reconstructed on demand.
4. Performance degrades linearly with event log length ($O(N)$), so Chronos introduces **snapshot-assisted state reconstruction**, reducing point-in-time calculation complexity to $O(k)$ where $k \ll N$.

---

## 2. Core Functional Requirements

### 2.1 Event Store & Ingestion
- **Immutable Event Append**: Support appending domain events with optimistic concurrency control (`expected_version`).
- **Event Ordering**: Strict sequential sequence numbering per aggregate/entity root.
- **Idempotent Ingestion**: Unique request/correlation keys to prevent duplicate event ingestion.

### 2.2 Temporal State Reconstruction Engine
- **Point-in-Time Queries**: Query entity state at exact timestamp (`ISO-8601`) or sequence version.
- **State Replay**: Replay events from sequence `0` (or snapshot sequence `S`) to target point `T`.
- **Temporal Diffing**: Calculate before/after delta diffs between two timestamps $T_1$ and $T_2$.

### 2.3 Snapshots & Performance Optimization
- **Periodic Snapshotting**: Generate state snapshots every $K$ events or on explicit API trigger.
- **Snapshot Selection**: Query engine automatically selects the nearest preceding snapshot $S \le T_{\text{target}}$ and replays remaining events up to $T_{\text{target}}$.

### 2.4 Asynchronous Projections & Read Models
- **Event Propagation**: Broadcast appended events to Apache Kafka topics.
- **Read Model Projection**: Asynchronously populate query-optimized PostgreSQL / Redis read models.
- **Projection Rebuilding**: Capability to truncate and replay entire event streams to rebuild read models zero-downtime.

### 2.5 Developer Tooling & UI
- **Visual Event Timeline**: React-based interactive timeline displaying sequence, timestamp, payload, and payload diffs.
- **Time-Travel Slider**: Interactively scrub back and forth in time to inspect reconstructed entity state live.

---

## 3. Non-Functional Requirements & Performance Targets

| Metric / Attribute | Target Requirement | Verification Method |
| :--- | :--- | :--- |
| **Point-in-Time Query Latency** | $< 50\text{ms}$ (with snapshot assistance) | JMH / Spring Boot Integration Benchmarks |
| **Event Ingestion Throughput** | $> 1,000\text{ events/sec}$ per partition | Load testing via K6 / Locust |
| **Data Consistency** | Strong consistency on Event Store writes; Eventual consistency on Read Projections | Concurrency testing & Testcontainers suite |
| **Fault Tolerance** | Automatic DLQ routing for malformed projection messages; optimistic locking fallback | Unit & Chaos Integration Tests |

---

## 4. Initial System Architecture

```text
                                +-----------------------------------+
                                |            Client / UI            |
                                |     (React + Visualizer UI)       |
                                +-----------------+-----------------+
                                                  |
                                                  v
                                +-----------------+-----------------+
                                |      Spring Boot REST API         |
                                |   (Command / Temporal Query API)  |
                                +--------+----------------+---------+
                                         |                |
                       Append Event      |                | Read / Reconstruct
                       (Version Check)   v                v (Snapshot + Replay)
                       +-----------------+--+   +---------+-----------------+
                       | PostgreSQL Event |   | State Reconstruction    |
                       | Store (Append)   |   | Engine                  |
                       +--------+---------+   +---------+-----------------+
                                |                       ^
                                | Kafka Producer        | Fetch Snapshot
                                v                       | & Events
                       +--------+---------+   +---------+-----------------+
                       |   Apache Kafka   |   | PostgreSQL Snapshot     |
                       |   Event Topics   |   | & Redis Cache           |
                       +--------+---------+   +-------------------------+
                                |
                                v Projection Consumer
                       +--------+---------+
                       | Read Projections |
                       | (Query Models)   |
                       +------------------+
```

---

## 5. Technology Stack Selection & Justification

1. **Java 21 & Spring Boot 3.x**:
   - Modern LTS Java features (Virtual Threads, Records, Pattern Matching).
   - Enterprise standard for backend systems engineering roles.
2. **PostgreSQL 16**:
   - Stores immutable events (`events` table) with `JSONB` for event payloads and indexes on `(entity_id, version)`.
   - Stores periodic entity snapshots (`snapshots` table).
3. **Apache Kafka**:
   - High-throughput distributed event streaming platform to decouple event persistence from read model projections.
4. **Redis**:
   - Fast cache layer for latest entity states and snapshot warm-ups.
5. **Testcontainers & JUnit 5**:
   - Real containerized integration tests running PostgreSQL, Kafka, and Redis during build pipeline.
6. **Docker & Docker Compose**:
   - One-command local development environment (`docker-compose up`).

---

## 6. Development Phases Roadmap

- **Phase 0 (Current)**: Specification, Architecture, and Project Setup.
- **Phase 1**: Domain & Event Model Design (Events, Commands, Aggregates).
- **Phase 2**: Core Event Store Implementation (PostgreSQL Append-Only Log with Optimistic Concurrency).
- **Phase 3**: Temporal Reconstruction Engine & Point-in-Time Query API.
- **Phase 4**: Snapshot Subsystem & Benchmark Harness.
- **Phase 5**: Apache Kafka Integration & Asynchronous Read Projections.
- **Phase 6**: Concurrency, Idempotency, and Failure Recovery (DLQ, Re-delivery).
- **Phase 7**: React + TypeScript Visual Event Timeline & Time-Travel UI.
- **Phase 8**: Performance Benchmarking & Profiling (Replay Latency vs Event Volume).
- **Phase 9**: Observability (Prometheus + Grafana), Dockerization, and CI/CD.
- **Phase 10**: Portfolio Packaging, Architecture Diagrams, and Interview Defense Preparation.

---

## 7. Non-Goals & Boundaries
- **NOT a Mobile/GPS Safety App**: Intentionally distinct from CareWave to demonstrate deep backend/systems capabilities.
- **NOT a Generic CRUD Application**: Focus is strictly on event sourcing, temporal reconstruction, and distributed state consistency.
- **No Unjustified Technologies**: Every framework (Kafka, Redis, PostgreSQL) is strictly tied to explicit performance/architectural constraints.
