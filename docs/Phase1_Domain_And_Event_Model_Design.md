# Chronos — Phase 1: Domain & Event Model Specification

**Status:** Authoritative Specification Baseline (v1.2 — Cleaned)  
**Version:** 1.2  
**Domain:** Financial Account / Ledger Aggregate (Reference Domain)  
**Project Lead / Author:** Saish Sanas
**Development Approach:** AI-assisted engineering

---

## 1. Aggregate Boundaries & Core Account State

The `AccountAggregate` is the primary consistency boundary. Its state is strictly encapsulated and derived purely by folding domain events.

### Account State Model (`AccountState` Record)
```java
public record AccountState(
    UUID accountId,
    String currency,              // ISO-4217 code (e.g. "INR")
    long balanceMinor,            // Integer minor units (paise); AccountCreated sets balance to 0
    long overdraftLimitMinor,     // Credit/overdraft limit in minor units
    long transactionLimitMinor,   // Maximum single-transaction withdrawal limit in minor units
    AccountStatus status,         // UNINITIALIZED, ACTIVE, FROZEN, CLOSED
    long sequenceNumber,          // Current aggregate sequence/version (starts at 1)
    Instant lastUpdatedAt         // Recorded timestamp of latest applied event
) {}
```

### Account Status Lifecycle Enum
- `UNINITIALIZED`: Default zero state before `AccountCreated` event.
- `ACTIVE`: Normal operating state (deposits & withdrawals allowed within balance, overdraft limit, and transaction limit).
- `FROZEN`: Administrative hold (deposits allowed; withdrawals/transfers rejected).
- `CLOSED`: Terminal state (all state-changing commands rejected).

---

## 2. Command Catalogue & Domain Invariants

Commands express user or system intent. If valid, a command produces one or more immutable Domain Events.

| Command Name | Parameters | Invariants & Validation Rules | Emitted Event(s) |
| :--- | :--- | :--- | :--- |
| `CreateAccount` | `accountId`, `currency`, `initialOverdraftLimitMinor`, `initialTransactionLimitMinor` | Account must be `UNINITIALIZED`. Currency must be valid ISO-4217 code. `initialOverdraftLimitMinor` $\ge 0$, `initialTransactionLimitMinor` $> 0$. Initializes `balanceMinor` to 0. | `AccountCreated` |
| `DepositMoney` | `accountId`, `amountMinor`, `idempotencyKey` | Account must be `ACTIVE` or `FROZEN`. `amountMinor` $> 0$. | `MoneyDeposited` |
| `WithdrawMoney` | `accountId`, `amountMinor`, `idempotencyKey` | Account must be `ACTIVE`. `amountMinor` $> 0$. `amountMinor <= transactionLimitMinor`. `(balanceMinor - amountMinor) >= -overdraftLimitMinor`. | `MoneyWithdrawn` |
| `FreezeAccount` | `accountId`, `reason` | Account must be `ACTIVE`. | `AccountFrozen` |
| `UnfreezeAccount` | `accountId`, `reason` | Account must be `FROZEN`. | `AccountUnfrozen` |
| `SetOverdraftLimit`| `accountId`, `newOverdraftLimitMinor` | Account must NOT be `CLOSED`. `newOverdraftLimitMinor` $\ge 0$. `balanceMinor >= -newOverdraftLimitMinor`. | `OverdraftLimitChanged` |
| `SetTransactionLimit` | `accountId`, `newTransactionLimitMinor` | Account must NOT be `CLOSED`. `newTransactionLimitMinor` $> 0$. | `TransactionLimitChanged` |
| `IssueCorrection` | `accountId`, `targetEventId`, `correctionType`, `direction`, `adjustmentAmountMinor`, `reason` | Requires `ADMIN` authority. Account must NOT be `CLOSED`. `targetEventId` must exist in stream. `correctionType` MUST be `REVERSAL` or `PARTIAL_ADJUSTMENT`. `direction` MUST be `CREDIT` or `DEBIT`. `adjustmentAmountMinor` $> 0$. Resulting balance $\ge -overdraftLimitMinor$. Compensating action against `targetEventId` only. | `CorrectionIssued` |
| `CloseAccount` | `accountId`, `reason` | Account must NOT be `CLOSED`. `balanceMinor == 0` (zero net balance required). | `AccountClosed` |

---

## 3. Multi-Event Causation Semantics

When a single command generates multiple domain events atomically:
- All emitted events share the exact same `correlationId` (client operation trace ID).
- All emitted events share the exact same `causationId` (the originating command ID).
- Events are assigned strictly sequential, consecutive `sequenceNumber` values (e.g., $S_k, S_{k+1}, S_{k+2}$).
- All events in the batch are committed to `event_store` and `event_outbox` within a single PostgreSQL database transaction.

---

## 4. Event Envelope & Event Catalogue Schemas

### 4.1 Base Event Envelope Schema
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["eventId", "aggregateId", "aggregateType", "sequenceNumber", "eventType", "eventVersion", "recordedAt", "metadata", "payload"],
  "properties": {
    "eventId": { "type": "string", "format": "uuid" },
    "aggregateId": { "type": "string", "format": "uuid" },
    "aggregateType": { "type": "string", "enum": ["Account"] },
    "sequenceNumber": { "type": "integer", "minimum": 1 },
    "eventType": { "type": "string" },
    "eventVersion": { "type": "integer", "minimum": 1 },
    "recordedAt": { "type": "string", "format": "date-time" },
    "metadata": {
      "type": "object",
      "required": ["correlationId", "causationId", "actorId"],
      "properties": {
        "correlationId": { "type": "string", "format": "uuid" },
        "causationId": { "type": "string", "format": "uuid" },
        "actorId": { "type": "string" },
        "idempotencyKey": { "type": ["string", "null"] }
      }
    },
    "payload": { "type": "object" }
  }
}
```

### 4.2 Specific Event Payloads (v1)

1. **`AccountCreated`**
   ```json
   {
     "currency": "INR",
     "initialOverdraftLimitMinor": 500000,
     "initialTransactionLimitMinor": 1000000
   }
   ```
   *Note: Initializes `balanceMinor` to 0.*

2. **`MoneyDeposited`**
   ```json
   {
     "amountMinor": 100000,
     "currency": "INR",
     "resultingBalanceMinor": 100000
   }
   ```

3. **`MoneyWithdrawn`**
   ```json
   {
     "amountMinor": 30000,
     "currency": "INR",
     "resultingBalanceMinor": 70000
   }
   ```

4. **`AccountFrozen`**
   ```json
   {
     "reason": "Administrative hold for verification"
   }
   ```

5. **`AccountUnfrozen`**
   ```json
   {
     "reason": "Verification completed successfully"
   }
   ```

6. **`OverdraftLimitChanged`**
   ```json
   {
     "newOverdraftLimitMinor": 1000000,
     "previousOverdraftLimitMinor": 500000
   }
   ```

7. **`TransactionLimitChanged`**
   ```json
   {
     "newTransactionLimitMinor": 2000000,
     "previousTransactionLimitMinor": 1000000
   }
   ```

8. **`CorrectionIssued`**
   ```json
   {
     "targetEventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
     "correctionType": "REVERSAL",
     "direction": "CREDIT",
     "adjustmentAmountMinor": 30000,
     "reason": "Reversal of erroneous duplicate withdrawal"
   }
   ```
   *Note: `correctionType` MUST be `REVERSAL` or `PARTIAL_ADJUSTMENT`. `direction` MUST be `CREDIT` or `DEBIT`.*

9. **`AccountClosed`**
   ```json
   {
     "reason": "Customer request"
   }
   ```

---

## 5. Event-Stream Integrity Validation

During historical state reconstruction or current state reduction, Chronos enforces strict stream integrity checks prior to applying each event. If any validation fails, reconstruction halts immediately with a typed `CorruptedEventStreamException`:

1. **Aggregate Identity Check**: Every event in the stream must match `event.aggregateId() == expectedAggregateId`.
2. **Strict Sequence Continuity**: For events $E_i$ and $E_{i+1}$, sequence numbers must satisfy $S_{i+1} = S_i + 1$ with no gaps or duplicate versions (starting at sequence $S_1 = 1$).
3. **Known Event Type Validation**: Event types must map to registered domain handlers.
4. **Timestamp Monotonicity**: `recordedAt` timestamps must satisfy $T_{i+1} \ge T_i$.

---

## 6. Pure Functional Reducer (`AccountReducer`)

State transitions are pure, deterministic, and side-effect free.

```java
public final class AccountReducer {

    public static AccountState reduce(AccountState current, DomainEventEnvelope event) {
        // Stream Integrity Validation
        if (current.status() != AccountStatus.UNINITIALIZED && !current.accountId().equals(event.aggregateId())) {
            throw new CorruptedEventStreamException("Aggregate ID mismatch: expected " + current.accountId() + " but got " + event.aggregateId());
        }
        if (current.sequenceNumber() > 0 && event.sequenceNumber() != current.sequenceNumber() + 1) {
            throw new CorruptedEventStreamException("Sequence gap detected: current " + current.sequenceNumber() + " -> event " + event.sequenceNumber());
        }

        long newSequence = event.sequenceNumber();
        Instant timestamp = event.recordedAt();

        return switch (event.eventType()) {
            case "AccountCreated" -> {
                String currency = event.getPayloadString("currency");
                long overdraft = event.getPayloadLong("initialOverdraftLimitMinor");
                long txLimit = event.getPayloadLong("initialTransactionLimitMinor");
                yield new AccountState(event.aggregateId(), currency, 0L, overdraft, txLimit, AccountStatus.ACTIVE, newSequence, timestamp);
            }
            case "MoneyDeposited" -> {
                long amount = event.getPayloadLong("amountMinor");
                yield new AccountState(current.accountId(), current.currency(), current.balanceMinor() + amount, current.overdraftLimitMinor(), current.transactionLimitMinor(), current.status(), newSequence, timestamp);
            }
            case "MoneyWithdrawn" -> {
                long amount = event.getPayloadLong("amountMinor");
                yield new AccountState(current.accountId(), current.currency(), current.balanceMinor() - amount, current.overdraftLimitMinor(), current.transactionLimitMinor(), current.status(), newSequence, timestamp);
            }
            case "AccountFrozen" -> 
                new AccountState(current.accountId(), current.currency(), current.balanceMinor(), current.overdraftLimitMinor(), current.transactionLimitMinor(), AccountStatus.FROZEN, newSequence, timestamp);
            case "AccountUnfrozen" -> 
                new AccountState(current.accountId(), current.currency(), current.balanceMinor(), current.overdraftLimitMinor(), current.transactionLimitMinor(), AccountStatus.ACTIVE, newSequence, timestamp);
            case "OverdraftLimitChanged" -> {
                long newLimit = event.getPayloadLong("newOverdraftLimitMinor");
                yield new AccountState(current.accountId(), current.currency(), current.balanceMinor(), newLimit, current.transactionLimitMinor(), current.status(), newSequence, timestamp);
            }
            case "TransactionLimitChanged" -> {
                long newLimit = event.getPayloadLong("newTransactionLimitMinor");
                yield new AccountState(current.accountId(), current.currency(), current.balanceMinor(), current.overdraftLimitMinor(), newLimit, current.status(), newSequence, timestamp);
            }
            case "CorrectionIssued" -> {
                String direction = event.getPayloadString("direction");
                long amount = event.getPayloadLong("adjustmentAmountMinor");
                long delta = "CREDIT".equalsIgnoreCase(direction) ? amount : -amount;
                yield new AccountState(current.accountId(), current.currency(), current.balanceMinor() + delta, current.overdraftLimitMinor(), current.transactionLimitMinor(), current.status(), newSequence, timestamp);
            }
            case "AccountClosed" -> 
                new AccountState(current.accountId(), current.currency(), current.balanceMinor(), current.overdraftLimitMinor(), current.transactionLimitMinor(), AccountStatus.CLOSED, newSequence, timestamp);
            default -> throw new IllegalArgumentException("Unknown event type: " + event.eventType());
        };
    }
}
```

---

## 7. Command Idempotency & Replay Semantics

### Uniqueness Constraint
The idempotency store enforces a database-level unique constraint on `(actor_id, idempotency_key)`.

### Same-Key Request Behaviors
1. **Identical Request Payload**: If a command arrives with a matching `(actor_id, idempotency_key)` and an identical request payload hash, Chronos skips command re-execution and returns the stored HTTP status code and response payload.
2. **Materially Different Payload**: If a command arrives with a matching `(actor_id, idempotency_key)` but a different request payload hash, Chronos rejects the request immediately with HTTP `409 Conflict`.

---

## 8. Relational Database Schema DDL (PostgreSQL 16)

```sql
-- 1. Core Append-Only Event Store Table
CREATE TABLE event_store (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT NOT NULL DEFAULT 1,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT uk_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX idx_event_store_lookup ON event_store (aggregate_id, sequence_number ASC);
CREATE INDEX idx_event_store_recorded ON event_store (aggregate_id, recorded_at ASC);

-- 2. Transactional Outbox Table (Stores Complete Envelope JSONB)
CREATE TABLE event_outbox (
    outbox_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES event_store(event_id),
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_envelope JSONB NOT NULL, -- Complete event envelope including metadata, payload, recorded_at, etc.
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_outbox_unprocessed ON event_outbox (created_at ASC) WHERE processed_at IS NULL;

-- 3. Snapshots Table
CREATE TABLE aggregate_snapshots (
    snapshot_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    sequence_number BIGINT NOT NULL,
    snapshot_version INT NOT NULL DEFAULT 1,
    domain_version INT NOT NULL DEFAULT 1,
    replay_logic_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state_payload JSONB NOT NULL,
    CONSTRAINT uk_snapshot_aggregate_sequence UNIQUE (aggregate_id, sequence_number)
);

CREATE INDEX idx_snapshots_latest ON aggregate_snapshots (aggregate_id, sequence_number DESC);

-- 4. Command Idempotency Table (Scoped by actor_id + idempotency_key)
CREATE TABLE command_idempotency (
    idempotency_key VARCHAR(256) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_status INT NOT NULL,
    response_payload JSONB NULL,
    CONSTRAINT pk_command_idempotency PRIMARY KEY (actor_id, idempotency_key)
);
```
