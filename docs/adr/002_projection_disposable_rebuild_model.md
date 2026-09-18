# ADR 002: Projection as Disposable Read Model & Staging Rebuild Strategy

## Status
Approved

## Context
In Chronos event sourcing, the `event_store` table is the immutable single source of truth. Read model projections (such as `account_summary_projection`) are disposable derived views designed for high-performance CQRS queries. Because projections can experience drift, schema evolution, or corruption, the system must provide a safe mechanism to rebuild projections from the authoritative `event_store`.

Kafka is used strictly as an asynchronous transport layer and is **not** the historical source of truth for projection rebuilds.

## Decision
We implement a zero-downtime projection rebuild workflow operating directly from the `event_store`:

1. **Event Store Primacy**: Rebuilds reconstruct aggregate state by reading historical event streams directly from PostgreSQL `event_store` and reducing state via the canonical `AccountReducer`.
2. **Staging Cutover (Zero Partial State Exposure)**:
   - During full projection rebuild, rows are computed and inserted into a staging table (`account_summary_projection_staging`).
   - Live query readers continue reading from `account_summary_projection` without witnessing partial or intermediate states.
   - Upon completion, an atomic staging-to-live swap (`TRUNCATE` + `INSERT SELECT`) is executed inside a single database transaction.
3. **Cache Invalidation**: Following cutover activation, the Redis `chronos:account-summary:*` cache is flushed via `redisCacheService.clearAll()` to prevent stale pre-rebuild reads.
4. **Rebuild Job Tracking**: Rebuild operations are tracked durably in `projection_rebuild_jobs` with status (`REQUESTED`, `RUNNING`, `SUCCEEDED`, `FAILED`), processed event counts, and resulting sequence numbers.
5. **Consistency Verification**: `ProjectionVerificationService` compares read model records against authoritative `event_store` state field-by-field (`balanceMinor`, `sequenceNumber`, `status`, limits) to detect any projection drift.

## Consequences
- **Positive**: Projections can be wiped and completely rebuilt at any time without exposing partial state or corrupting live reads.
- **Trade-off**: Full rebuilds require transient database storage for the staging table equal to the number of active aggregates.
