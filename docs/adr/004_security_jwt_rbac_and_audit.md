# ADR 004: Stateless JWT Authentication, Role-Based Access Control, Actor Identity Binding, and Append-Only Security Audit Logging

## Status
Approved

## Context
Chronos operates as an event-sourced temporal state reconstruction engine and high-integrity banking core. Prior waves focused on aggregate modeling, deterministic temporal replays, optimistic concurrency control, and outbox event publishing without an authentication or authorization boundary.

Production operation requires:
1. **Strong Authentication**: Verifying caller identities using stateless, cryptographically signed tokens without server-side session state.
2. **Role-Based Access Control (RBAC)**: Enforcing strict separation of duties between command operators, temporal auditors, and system administrators.
3. **Immutable Actor Identity Attribution**: Tying financial commands and domain events to immutable, verified identities to prevent spoofing or repudiation.
4. **Authoritative Event Sourcing Separation**: Keeping core domain event contracts, schemas, and reducers pure and security-agnostic while recording security-relevant lifecycle actions in a dedicated audit log.
5. **Resilient Audit Boundary**: Ensuring audit log persistence failures do not disrupt authoritative financial transactions or cause silent transaction rollbacks.

## Decision
We implement a defense-in-depth security architecture centered on Spring Security, stateless Bearer JWTs, authoritative actor binding, and an append-only audit log:

### 1. Stateless JWT Authentication & Configuration
- **HMAC-SHA256 Signing**: Access tokens are signed using HMAC-SHA256 (`NimbusJwtDecoder` / `NimbusJwtEncoder`).
- **Subject & Claims**: The JWT `sub` (subject) is authoritatively set to the user's stable UUID (`userId.toString()`). Custom claims include `username` (human-readable display/audit attribution) and `roles` (array of assigned authorities).
- **Zero-Secret Base Configuration**: Base `application.yml` leaves `chronos.security.jwt.secret` unset (`${CHRONOS_JWT_SECRET:}`). Real environments must provide the secret through an environment variable. Deterministic secrets are strictly isolated to test fixtures and local docker-compose profiles.
- **Stateless Session Management**: Spring Security `SessionCreationPolicy.STATELESS` ensures no server-side HTTP sessions are allocated.

### 2. Role-Based Access Control (RBAC)
Three distinct operational roles govern access:
- **`ROLE_OPERATOR`**: Permitted to issue financial commands (`POST /api/v1/accounts/**`), read accounts, read event streams, and inspect temporal snapshots.
- **`ROLE_AUDITOR`**: Granted read-only inspection access across accounts, event histories, temporal replays, and security audit logs (`GET /api/v1/ops/audit`). Prohibited from issuing financial commands or projection rebuilds.
- **`ROLE_ADMIN`**: Super-user access. Permitted to execute operator/rebuild operations (`POST /api/v1/ops/projections/**`), read security audit logs, access sensitive Actuator endpoints (`/actuator/**` except `/health` and `/info`), and perform administrative actions.

### 3. Actuator Hardening
- Public endpoints are strictly limited to `/actuator/health` and `/actuator/info`.
- All sensitive management endpoints (`/actuator/metrics`, `/actuator/env`, `/actuator/beans`, etc.) require authenticated `ROLE_ADMIN` access.

### 4. Authoritative Actor Identity Binding
- In incoming account command requests, the `CommandContext.actorId` is not supplied by client input or username.
- The `AccountController` extracts the authenticated `SecurityPrincipal` from `SecurityContextHolder` and authoritatively binds `CommandContext.actorId` to `principal.userId().toString()`.
- Idempotency deduplication keys (`actor_id`, `idempotency_key`) are therefore bound directly to the verified UUID, preventing cross-tenant or cross-operator collision/spoofing.

### 5. Append-Only Security Audit Store
- A dedicated PostgreSQL table `security_audit_log` records all security events (`LOGIN_SUCCESS`, `LOGIN_FAILURE`, `ACCOUNT_COMMAND`, `PROJECTION_REBUILD`, `ACCESS_DENIED`).
- Includes `event_id`, `action`, `actor_id` (UUID string), `actor_username`, `resource_type`, `resource_id`, `ip_address`, `status`, `details` (JSONB), and `created_at`.
- **Fault-Tolerant Non-Blocking Boundary**: Security audit writes execute inside dedicated `SecurityAuditService` with try-catch isolation. In accordance with event-sourcing principles, `event_store` remains the sole authoritative source for financial transitions. A failure in audit log persistence increments `chronos.security.audit.write.failure` and logs a structured error, but never aborts or rolls back the authoritative financial command.

### 6. Development User Bootstrap
- The `DevelopmentUserBootstrap` seeds default accounts (`admin`, `operator`, `auditor`) only when `chronos.security.bootstrap-dev-users` is set to `true`.
- The default in `application.yml` is `false`. Production deployments will never automatically bootstrap default credentials.

## Consequences
- **Positive**: Strict defense-in-depth, zero credential leakage into client source code, tamper-evident security audit trail, and zero modification to pure domain event contracts.
- **Trade-off**: Requires clients to authenticate via `POST /api/v1/auth/login` and attach `Authorization: Bearer <token>` to protected endpoints.
