# Chronos Engine v1.0
> **Time-Traveling State Reconstruction Engine with Transactional Event Sourcing, Asynchronous Kafka Broadcast, CQRS Read Model Caching, and React Temporal Visualization Dashboard.**

### Live Demo

**🌐 Live Application:** https://chronos-frontend-g0m4.onrender.com

**⚙️ Backend API:** https://chronos-engine-4mci.onrender.com

**📖 Swagger / API Docs:** https://chronos-engine-4mci.onrender.com/swagger-ui/index.html

**💻 GitHub Repository:** https://github.com/saishsanas/chronos

[![Build & Test](https://github.com/saishsanas/chronos/actions/workflows/ci.yml/badge.svg)](https://github.com/saishsanas/chronos/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache%20Kafka-7.6.0-black.svg)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)
[![React](https://img.shields.io/badge/React-19-cyan.svg)](https://react.dev/)

---

## 1. Live Deployment

Chronos is publicly deployed on a zero-cost ($0 / ₹0) cloud infrastructure architecture:

```
React / Vite Frontend
        ↓
Render Free Static Site (chronos-frontend)
        ↓
Spring Boot REST API (chronos-engine)
        ↓
Neon Free PostgreSQL
```

- **Live Dashboard:** https://chronos-frontend-g0m4.onrender.com
- **Live REST API:** https://chronos-engine-4mci.onrender.com
- **Swagger / API Docs:** https://chronos-engine-4mci.onrender.com/swagger-ui/index.html
- **Database Provider:** Neon Serverless PostgreSQL (Free Tier)
- **Deployment Platform:** Render Cloud Platform (Free Web Service + Free Static Site)
- **Zero-Cost Guarantee:** Entire stack runs within 100% free-tier resource allocation ($0 / ₹0).

---

## 2. Project Purpose
Traditional CRUD financial applications suffer from state mutability, audit trail destruction, and lack of temporal visibility into historical system states. **Chronos** solves these challenges by implementing an immutable **Event Sourcing** architecture with **Snapshot Optimization**, **Transactional Outbox Messaging**, **Idempotent Kafka Consumer Processing**, **CQRS Read Model Projections with Redis Caching**, and **Inclusive Temporal State Reconstruction (`stateAt(T)`)** served via a **Dark-First React Observability Dashboard**.

### User Interface & Observability Dashboard

![Chronos Dashboard](docs/images/chronos-dashboard.png)
*Chronos dashboard — reconstructed account state, domain commands, and temporal replay controls.*

![Chronos Temporal Replay](docs/images/chronos-temporal-replay.png)
*Temporal reconstruction — historical state at T compared with the current aggregate head.*

---

## 3. High-Level Architecture

```mermaid
flowchart TD
    subgraph UI ["React Temporal Visualization Frontend"]
        Dashboard["Dashboard UI (React / Vite / Tailwind)"]
        Inspector["Temporal Replay Inspector (stateAt T)"]
        Timeline["Event Stream Timeline"]
    end

    subgraph API ["Spring MVC REST API"]
        Controller["AccountController"]
        Filter["MdcCorrelationFilter"]
        WebConfig["CORS & WebConfig"]
    end

    subgraph DomainApp ["Application & Domain Engine"]
        Processor["AccountCommandProcessor"]
        Upcaster["EventUpcasterRegistry (Dynamic Schema Evolution)"]
        Reducer["AccountReducer"]
        Reconstructor["TemporalStateReconstructor"]
        SummaryService["AccountSummaryQueryService"]
    end

    subgraph ReadModel ["CQRS Read Model & Caching"]
        RedisCache[("Redis Cache (60s TTL)")]
        ReadProjection[("account_summary_projection")]
        ProjectionHandler["AccountProjectionHandler"]
    end

    subgraph Persistence ["PostgreSQL Storage Layer"]
        EventStore[("event_store (Source of Truth)")]
        Snapshots[("snapshots (Derived Optimization)")]
        OutboxTable[("outbox_events (Publication Intent)")]
        InboxTable[("inbox_events (Consumer Record)")]
        SeqTable[("consumer_aggregate_state")]
    end

    subgraph Messaging ["Distributed Event Bus"]
        OutboxRelay["OutboxPublisher Relay (SKIP LOCKED)"]
        KafkaBus["Apache Kafka (chronos.events.v1)"]
        InboxConsumer["KafkaEventConsumer (Manual Ack)"]
    end

    Dashboard -->|HTTP REST| Filter
    Filter --> Controller
    Controller -->|Commands| Processor
    Controller -->|Temporal Queries| Reconstructor
    Controller -->|CQRS Summary| SummaryService

    SummaryService -->|1. Cache Read| RedisCache
    SummaryService -->|2. Fallback Read| ReadProjection

    Processor -->|Load Stream| EventStore
    EventStore -->|Raw Envelopes| Upcaster
    Upcaster -->|Canonical Events| Reducer
    Processor -->|Apply Events| Reducer
    Processor -->|Atomic Write| EventStore
    Processor -->|Atomic Write| OutboxTable

    Reconstructor -->|Select Latest Snapshot| Snapshots
    Reconstructor -->|Replay recordedAt <= T| EventStore

    OutboxTable -->|Poll FOR UPDATE SKIP LOCKED| OutboxRelay
    OutboxRelay -->|Broadcast JSON Envelope| KafkaBus

    KafkaBus -->|At-Least-Once Delivery| InboxConsumer
    InboxConsumer -->|Deduplicate & Validate| InboxTable
    InboxConsumer -->|Update CQRS Read Model| ProjectionHandler
    ProjectionHandler -->|Write Projection| ReadProjection
    ProjectionHandler -->|Invalidate/Write Cache| RedisCache
```

---

## 4. Core Architectural Guarantees

| Guarantee | Mechanism / Implementation |
| :--- | :--- |
| **Immutable Source of Truth** | The `event_store` table is append-only. Events are never modified or deleted. |
| **Deterministic Schema Upcasting** | Dynamic in-memory migration of legacy events (v1 -> v2) via `EventUpcasterRegistry` while strictly preserving raw event store immutability. |
| **Database-Enforced Command Idempotency** | Unique constraint on `(actor_id, idempotency_key)` in `command_idempotency` with SHA-256 fingerprinting prevents race conditions and returns original results without duplicate event creation. Conflict reuse triggers HTTP 409. |
| **Projection Rebuild & Atomic Cutover** | Zero-downtime projection rebuild from `event_store` via `account_summary_projection_staging` and atomic transaction swap (`TRUNCATE` + `INSERT SELECT`). Flushes Redis cache on activation. |
| **Poison Event Quarantine** | Malformed Kafka payloads transition to `QUARANTINED` status in `inbox_events` with error details, preventing infinite consumer retries while unblocking the event bus. |
| **Optimistic Concurrency Control (OCC)** | Aggregate versioning prevents sequence drift and lost updates under concurrent commands. |
| **Inclusive Temporal Replay** | `stateAt(T)` reconstructs exact aggregate state for events where `recordedAt <= T`. |
| **Atomic Outbox Publication** | `event_store` and `outbox_events` are populated in **one single PostgreSQL database transaction**. |
| **Lock-Free Outbox Polling** | Outbox relay uses `FOR UPDATE SKIP LOCKED` for non-blocking concurrent worker scaling. |
| **Inbox Consumer Idempotency** | `inbox_events` table with unique `event_id` constraint guarantees at-least-once Kafka transport is effectively idempotent at the consumer boundary. |
| **CQRS Read Model Caching** | Low-latency `GET /summary` query via Redis cache (60s TTL) with automatic fallback to PostgreSQL. |
| **Visual Temporal Audit** | React frontend provides interactive time-travel replay, sequence timeline, and JSON envelope inspection. |

---

## 5. Technology Stack
- **Backend:** Java 21 LTS, Spring Boot 3.3.4 (MVC, JDBC, Kafka, Redis, Actuator, Validation)
- **Frontend:** React 19, Vite, TypeScript 5.6, Tailwind CSS, Lucide Icons
- **Database & Migration:** PostgreSQL 16, Flyway Migration (`V1` to `V10`)
- **Security & Auth:** Spring Security 6.3, Nimbus JOSE JWT (HMAC-SHA256), BCrypt Password Hashing, RBAC
- **Messaging & Cache:** Apache Kafka 7.6.0 (KRaft mode), Redis 7
- **Documentation & Metrics:** SpringDoc OpenAPI 2.6.0 (Swagger UI Bearer JWT), Micrometer Telemetry
- **Containerization & CI:** Docker, Docker Compose, GitHub Actions

---

## 6. Security Architecture & RBAC

Chronos implements enterprise-grade, defense-in-depth security designed for financial core systems:

```mermaid
flowchart LR
    Client["Client / React UI"] -->|1. POST /api/v1/auth/login| AuthCtrl["AuthController"]
    AuthCtrl -->|Verify BCrypt| UserRepo[("application_users & user_roles")]
    AuthCtrl -->|2. Issue HMAC-SHA256 JWT| Client

    Client -->|3. Request with Bearer JWT| SecFilter["JwtAuthenticationFilter"]
    SecFilter -->|Validate & Extract Principal| SecCtx["SecurityContext (userId, username, roles)"]
    SecCtx -->|4. Authorize via RBAC| Endpoints{"Controller Boundary"}

    Endpoints -->|OPERATOR / ADMIN| CmdCtrl["AccountController (Binds actorId = userId)"]
    Endpoints -->|ADMIN| OpsCtrl["AccountOpsController (Projection Rebuild)"]
    Endpoints -->|AUDITOR / ADMIN| AuditCtrl["SecurityAuditController (Audit Trail)"]
    Endpoints -->|ADMIN only| Actuator["Actuator Management (/metrics, /env)"]

    CmdCtrl -->|Completed Authoritative Command| AuditService["SecurityAuditService (Non-blocking)"]
    OpsCtrl -->|Completed Projection Rebuild| AuditService
    AuditService -->|Append-Only Write| AuditLog[("security_audit_log")]
```

### Role-Based Access Control (RBAC) Matrix

| Endpoint / Resource | Anonymous | `OPERATOR` | `AUDITOR` | `ADMIN` |
| :--- | :---: | :---: | :---: | :---: |
| `POST /api/v1/auth/login` | Permit | Permit | Permit | Permit |
| `GET /actuator/health`, `/actuator/info` | Permit | Permit | Permit | Permit |
| `GET /api/v1/accounts/**` (State & Events) | Deny (401) | Allow | Allow | Allow |
| `POST /api/v1/accounts/**` (Financial Commands) | Deny (401) | Allow | Deny (403) | Allow |
| `POST /api/v1/ops/projections/**` (Rebuild Projections) | Deny (401) | Deny (403) | Deny (403) | Allow |
| `GET /api/v1/ops/audit` (Security Audit Log) | Deny (401) | Deny (403) | Allow | Allow |
| `/actuator/**` (Metrics, Env, Beans, etc.) | Deny (401) | Deny (403) | Deny (403) | Allow |

### Actor Identity Binding
When an authenticated operator or admin executes financial commands:
- `CommandContext.actorId` is **authoritatively bound** to the verified user's immutable `userId` UUID from the JWT subject (`principal.userId().toString()`).
- Clients cannot spoof actor identities or tamper with idempotency deduplication scopes (`actor_id`, `idempotency_key`).
- Domain event contracts and reducer state transitions remain pure, deterministic, and security-agnostic.

---

## 7. Role Privileges & Security Posture

Development and demonstration accounts are managed via environment variables. Production credentials and JWT signing keys are securely injected at runtime and are never committed to the repository.

| Role | Scope & Privileges |
| :--- | :--- |
| `ADMIN` | Full administrative control, projection rebuilds, audit log inspection, management actuator endpoints |
| `OPERATOR` | Account creation, deposits, withdrawals, account freezing/unfreezing, overdraft and transaction limits |
| `AUDITOR` | Read-only ledger verification, temporal state inspection (`stateAt`), security audit trail reviews |

---

## 8. REST API Reference

### Authentication
- `POST /api/v1/auth/login` — Authenticate with username and password, returns Bearer JWT with roles and expiration

### Financial Command Endpoints (Requires `OPERATOR` or `ADMIN`)
- `POST /api/v1/accounts` — Create a new account aggregate
- `POST /api/v1/accounts/{id}/deposits` — Deposit funds into account
- `POST /api/v1/accounts/{id}/withdrawals` — Withdraw funds (enforces transaction & overdraft limits)
- `POST /api/v1/accounts/{id}/freeze` — Freeze account (prevents withdrawals)
- `POST /api/v1/accounts/{id}/unfreeze` — Unfreeze account
- `PUT /api/v1/accounts/{id}/limits/overdraft` — Change overdraft limit
- `PUT /api/v1/accounts/{id}/limits/transaction` — Change transaction limit
- `POST /api/v1/accounts/{id}/corrections` — Issue financial reversal/adjustment correction
- `POST /api/v1/accounts/{id}/close` — Close zero-balance account

### Query & Temporal Endpoints (Requires `OPERATOR`, `AUDITOR`, or `ADMIN`)
- `GET /api/v1/accounts/{id}` — Reconstruct current state from snapshot and event stream
- `GET /api/v1/accounts/{id}/summary` — CQRS read model summary (Redis cached with PostgreSQL fallback)
- `GET /api/v1/accounts/{id}/state-at?at=<ISO-8601>` — Reconstruct historical state at timestamp $T$ (`recordedAt <= T`)
- `GET /api/v1/accounts/{id}/events` — Fetch full immutable event history (`ORDER BY sequenceNumber ASC`)

### Operations & Security Audit (Requires `ADMIN` / `AUDITOR`)
- `POST /api/v1/ops/projections/account-summary/rebuild` — Trigger zero-downtime projection rebuild (`ADMIN` only)
- `GET /api/v1/ops/audit` — Query bounded security audit log trail (`ADMIN` or `AUDITOR`)

---

## 9. Application Entry Points & Observability

### Production (Live Deployment)
- **Temporal Dashboard UI (React):** https://chronos-frontend-g0m4.onrender.com
- **Backend REST API:** https://chronos-engine-4mci.onrender.com
- **Interactive Swagger UI:** https://chronos-engine-4mci.onrender.com/swagger-ui/index.html
- **Health Check (Public):** https://chronos-engine-4mci.onrender.com/actuator/health
- **OpenAPI v3 Spec:** https://chronos-engine-4mci.onrender.com/v3/api-docs

### Local Development
- **Temporal Dashboard UI (React):** http://localhost:5173
- **Interactive Swagger UI:** http://localhost:8080/swagger-ui/index.html
- **Health Check:** http://localhost:8080/actuator/health
- **Application Info:** http://localhost:8080/actuator/info
- **Operational Metrics (`ADMIN` only):** http://localhost:8080/actuator/metrics

---

## 10. Swagger UI & Bearer JWT Testing

1. Open **Swagger UI**:
   - Production: [https://chronos-engine-4mci.onrender.com/swagger-ui/index.html](https://chronos-engine-4mci.onrender.com/swagger-ui/index.html)
   - Local: `http://localhost:8080/swagger-ui/index.html`
2. Expand `POST /api/v1/auth/login`, click **Try it out**, and submit your authentication credentials.
3. Copy the returned `accessToken` string from the JSON response.
4. Click the green **Authorize** button at the top right of the Swagger UI page.
5. In the **Value** field, enter `Bearer <token>` and click **Authorize**.
6. All subsequent command and query endpoints will execute with the authenticated Bearer token.

---

## 11. Local Execution & Test Guide

### Prerequisites
- JDK 21
- Node.js v22+ & npm 10+
- Maven 3.9+
- Docker & Docker Compose

### 1. Run Complete Test Suite (172 Tests)
```bash
mvn clean test
```
> [!NOTE]
> Requires JDK 21. If multiple Java versions are installed on your workstation, configure your `JAVA_HOME` environment variable to point to your JDK 21 installation prior to running Maven.

### 2. Run Frontend Build
```bash
cd frontend
npm install
npm run build
cd ..
```

### 3. Run Full System via Docker Compose
```bash
# Set a 256-bit JWT signing secret for local environment
export CHRONOS_JWT_SECRET="dev-insecure-only-secret-for-chronos-docker-compose-minimum-256-bits-ok!"
# On Windows PowerShell: $env:CHRONOS_JWT_SECRET="dev-insecure-only-secret-for-chronos-docker-compose-minimum-256-bits-ok!"

docker compose up --build
```
Once initialized, access the dashboard, Swagger UI, and health check via the URLs listed in [Section 9 (Application Entry Points & Observability)](#9-application-entry-points--observability).

---

## 12. Performance Benchmarking & Scale Validation (Wave 4)

Chronos includes an isolated, reproducible performance benchmark harness that empirically measures the engine under growing volume (10k, 50k, 100k events), diverse access patterns, and concurrent load without modifying or bypassing Waves 1–3 correctness, OCC, or security boundaries.

### Benchmark Highlights (100k Scale Baseline)
- **Full Replay**: 100,000 events replayed from PostgreSQL in **615.9 ms** (~162,350 events/sec sustained throughput).
- **Snapshot Acceleration**: 90% snapshot placement avoids 90,000 tail events, hydrating state in **60.2 ms** (**10.2x wall-clock speedup**).
- **Zero-Downtime Projection Rebuild**: Rebuilding read model across 500 accounts (100,000 events) completes in **1.42 seconds** via staging tables and atomic live cutover.
- **CQRS Read Model Latency**: Low-latency Redis cache reads (**1.45 ms**) vs PostgreSQL projection reads (**1.13 ms** direct / **4.38 ms** on miss + fallback).
- **OCC Under Contention**: 8 concurrent workers executing transactions against a single account achieve clean serialized commits while rejecting 68 expected race collisions via `OptimisticConcurrencyException` without sequence drift.
- **In-Memory Schema Evolution**: Legacy v1 $\to$ v2 upcasting overhead measured at **74 ns/event** via JMH 1.37 while strictly maintaining raw PostgreSQL event store immutability.

### Running Benchmarks
Benchmarks are isolated in `chronos_bench_db` and do not run during standard correctness builds:
```powershell
# Run primary benchmark suite (10k, 50k, 100k events)
.\scripts\run-benchmarks.ps1
```

For full methodology, statistical analysis, environment profile, and raw metrics tables, see the [Wave 4 Benchmark Report](docs/performance/WAVE4_BENCHMARK_REPORT.md).

---

## 13. License
Apache License 2.0. Built for production demonstration and technical portfolio review.
