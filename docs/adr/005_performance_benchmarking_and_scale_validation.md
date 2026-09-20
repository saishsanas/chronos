# ADR 005: Reproducible Performance Benchmarking, Scale Validation, and Engineering Measurement Harness

## Status
Approved

## Context
Chronos completed foundational milestones for reliability (Wave 1), schema evolution (Wave 2), and security (Wave 3). Prior to Wave 4, operational performance characteristics—such as temporal replay duration under deep streams, snapshot acceleration factors, projection rebuild throughput, CQRS read latencies, and concurrency behavior under lock contention—were unmeasured.

Evaluating these characteristics required a performance benchmarking harness subject to clear architectural constraints:
1. **Zero Compromise of Production Guarantees**: Benchmarking must test the actual production architecture without bypassing OCC, command idempotency, outbox persistence, dynamic upcasting, or security filters.
2. **Reproducibility & Statistical Discipline**: Measurements must be reproducible across environments with deterministic inputs and statistically defensible reporting.
3. **Strict Build & Database Isolation**: Long-running benchmark suites must never run during standard developer builds (`mvn clean test`) or contaminate development/test databases.
4. **Transparent Scope & Limitations**: Measurements must be reported as empirical regression baselines rather than universal capacity claims.

## Decision
We implement a dedicated, isolated performance benchmarking and scale validation harness for Chronos:

### 1. Dedicated Isolated Database (`chronos_bench_db`)
- Benchmarks execute exclusively against a dedicated PostgreSQL database: `chronos_bench_db`.
- Normal developer environments (`chronos_db`) and unit test databases (`chronos_test_db`) are never accessed or mutated during benchmark runs.
- `BenchmarkDatabaseInitializer` executes Flyway migrations (`V1` to `V10`) on `chronos_bench_db` and enforces a hard runtime assertion aborting any destructive reset on non-benchmark database URLs.

### 2. Deterministic Dataset Synthesis
- Workloads are generated via `DeterministicEventGenerator` using pure, repeatable formulas:
  - Fixed UUID namespaces derived via `UUID.nameUUIDFromBytes(...)` for aggregate IDs, event IDs, correlation IDs, and causation IDs.
  - Monotonically increasing deterministic timestamps starting at `2026-01-01T00:00:00Z` (+10s intervals).
  - Canonical event payloads containing valid business amounts and schema attributes.
  - Repeated generation of identical parameters yields bit-identical event streams.

### 3. Scale Tiers & Access Topologies
Workloads are evaluated across three scale tiers: **10,000**, **50,000**, and **100,000** events under two orthogonal topologies:
- **Topology A (Single Aggregate — Stream Depth Focus)**: Evaluates unassisted sequential replay, snapshot-assisted state hydration at 90% stream depth (seq 9k, 45k, 90k), point-in-time temporal reconstruction (`stateAt(T)` at 50% depth), and direct PostgreSQL `event_store` scanning.
- **Topology B (Multi-Aggregate — Projection & CQRS Scale Focus)**: Evaluates full zero-downtime projection rebuild across 500 accounts uniformly distributed into staging tables with atomic cutover, CQRS Redis read hits/misses, and multi-worker concurrent command writes.

### 4. Hybrid Benchmarking Methodology
- **In-Process Microbenchmarking**: Employs JMH 1.37 (`jmh-core`, `jmh-generator-annprocess`) to measure pure CPU overhead of in-memory schema upcasting (`EventUpcasterRegistry`) and `AccountReducer` folding.
- **Integration Benchmarking**: Measures full-system behavior against real PostgreSQL 16 and Redis 7 containers, incorporating connection pools (HikariCP), network loopback, query execution, JSON serialization, and transactional persistence.

### 5. Build Isolation & Statistical Rules
- **Surefire Exclusion**: Configured in `pom.xml` via `maven-surefire-plugin` to exclude `**/*Benchmark.java` and `**/*BenchmarkRunner.java`. Routine `mvn clean test` runs only unit and integration tests (172 tests) in under 75 seconds.
- **Execution via Maven Plugin**: Executed on demand via `exec-maven-plugin:3.4.1` (`mvn exec:java@benchmark`) or `scripts/run-benchmarks.ps1`.
- **$p_{95}$ Sample Threshold Rule**: In accordance with statistical guidelines, 95th-percentile latency ($p_{95}$) is calculated and reported **only when observation count $N \ge 20$**. For workloads with $N < 20$ (e.g. 100k event runs with $N = 10$ to prevent excessive table churn), $p_{95}$ is strictly suppressed (`null` / `N/A`) and median with min/max range is reported.

### 6. Scope Disclosures
- **Kafka Throughput Deferred**: Distributed broker performance is deferred because a multi-node broker benchmark cannot be reliably or reproducibly measured on a single-machine local test harness. Core outbox transactional persistence and inbox consumer idempotency correctness remain verified by integration tests.
- **Regression Evidence vs Capacity**: Benchmark results reflect the measured hardware, OS, and JVM environment and serve as an empirical regression baseline, not an absolute capacity guarantee.

## Consequences
- **Positive**: Provides trustworthy, empirical evidence of system scalability, proves snapshot acceleration (10.2x speedup at 100k events), verifies zero-downtime projection rebuilds under load, validates OCC conflict handling under concurrent contention, and establishes regression safety without slowing down routine test cycles.
- **Trade-off**: Running the full 10k/50k/100k scale suite requires running Docker containers (PostgreSQL and Redis) and takes several minutes of dedicated execution time.
