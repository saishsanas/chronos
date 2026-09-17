# Chronos: Time-Traveling State Reconstruction Engine

Chronos is a high-performance **Temporal State Reconstruction Engine** built in Java 21 and Spring Boot 3 using true event sourcing. Instead of mutating aggregate state in-place, Chronos records state changes as an append-only log of immutable domain events, enabling dynamic point-in-time aggregate reconstruction, temporal diffing, and event replay.

---

## 🚀 Current Project Status

**Current Phase:** Phase 2 — Event Store Core & Persistence Layer  
**Task Implemented:** Task 2.1 — Core Backend Bootstrap & Event Store Foundation  

### What Task 2.1 Implements
- **Spring Boot 3.3 Backend Skeleton** configured with Java 21 features.
- **Domain Event Primitives**: `DomainEventEnvelope`, `EventMetadata`, `AccountState` (8 fields), and `AccountStatus`.
- **`EventStore` Application Port Interface**: Clean hexagonal separation hiding PostgreSQL persistence details behind application interfaces.
- **`PostgresEventStore` Implementation**: High-throughput append-only event log backed by Spring `JdbcTemplate` and PostgreSQL 16 `JSONB`.
- **Optimistic Concurrency Control (OCC)**: Enforced via PostgreSQL `UNIQUE (aggregate_id, sequence_number)` database constraint and expected-version validation.
- **Flyway Migration `V1__create_event_store.sql`**: Versioned database schema setup with timezone-aware `TIMESTAMPTZ` recorded timestamps and indices.
- **Integration Test Suite**: 10 comprehensive tests running against real PostgreSQL containers via **Testcontainers**.

### Intentionally Out of Scope for Task 2.1
- Apache Kafka event streams & projections (Task 2.4 / Phase 4)
- Transactional Outbox Pattern (Task 2.3 / Phase 4)
- Snapshot-assisted reconstruction (Phase 3)
- Redis caching (Phase 4 / Phase 7)
- REST APIs, Authentication, & UI (Phases 6 & 8)

---

## 🛠 Local Environment & Execution Setup

### Prerequisites
- **Java**: JDK 21 LTS (OpenJDK / Temurin 21)
- **Maven**: 3.9+
- **Docker Desktop**: Required for running integration tests via Testcontainers or local PostgreSQL container.

### Running Integration Tests
To compile the application and run all 10 integration tests against real PostgreSQL 16 Testcontainers:
```bash
mvn clean test
```

### Running Local PostgreSQL Database via Docker
To launch a local PostgreSQL container for standalone application testing:
```bash
docker run -d \
  --name chronos-postgres \
  -e POSTGRES_DB=chronos_db \
  -e POSTGRES_USER=chronos_user \
  -e POSTGRES_PASSWORD=chronos_password \
  -p 5432:5432 \
  postgres:16-alpine
```

To run the Spring Boot application:
```bash
mvn spring-boot:run
```

---

## 📁 Modular Package Architecture

```text
com.chronos
├── ChronosApplication.java               # Spring Boot entry point
├── domain
│   ├── account
│   │   ├── AccountState.java            # Account record model (8 fields)
│   │   └── AccountStatus.java           # Account status lifecycle enum
│   └── event
│       ├── DomainEventEnvelope.java     # Immutable event envelope record
│       └── EventMetadata.java           # Tracing metadata (correlationId, causationId, etc.)
├── application
│   └── port
│       ├── EventStore.java              # Core Event Store interface port
│       ├── OptimisticConcurrencyException.java # OCC exception
│       └── CorruptedEventStreamException.java   # Stream integrity exception
└── infrastructure
    └── persistence
        └── postgres
            └── PostgresEventStore.java  # JDBC PostgreSQL EventStore implementation
```

---

## 🏷 Database Schema (`event_store`)

```sql
CREATE TABLE event_store (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    recorded_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT uk_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX idx_event_store_lookup ON event_store (aggregate_id, sequence_number ASC);
CREATE INDEX idx_event_store_recorded ON event_store (aggregate_id, recorded_at ASC);
```
