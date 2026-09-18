# ADR 001: Database-Enforced Command Idempotency Architecture

## Status
Approved

## Context
Command idempotency is essential to ensure that duplicate HTTP requests (caused by client retries, network hiccups, or message re-deliveries) do not create duplicate business effects or duplicate domain events. A naive `SELECT then INSERT` approach in application code is vulnerable to race conditions when concurrent duplicate requests arrive simultaneously.

## Decision
We enforce command idempotency at the application/API boundary using a dedicated PostgreSQL storage table `command_idempotency` with a database unique constraint on `(actor_id, idempotency_key)`:

1. **Database Uniqueness**: Concurrent requests racing with the same `(actor_id, idempotency_key)` collide on the database unique index. Only one request successfully inserts an `IN_FLIGHT` record; racing requests fail the insert and receive an idempotency conflict response.
2. **SHA-256 Request Fingerprinting**: The incoming request payload is fingerprinted using SHA-256 (`request_hash`).
3. **Same Key + Same Request**: Retried requests with matching `request_hash` return the cached HTTP response payload from `command_idempotency` without executing business logic or producing duplicate domain events.
4. **Same Key + Different Request**: Requests presenting a previously used `idempotency_key` with a different request payload trigger an HTTP 409 Conflict (`CommandIdempotencyConflictException`).
5. **In-Flight Concurrent Request**: Requests presenting a key currently marked `IN_FLIGHT` by another thread trigger an HTTP 409 Conflict.

## Consequences
- **Positive**: Guarantees zero duplicate business effects under concurrent retries. Database engine handles race condition enforcement atomically.
- **Trade-off**: Requires a small database write before command execution to register the `IN_FLIGHT` state.
