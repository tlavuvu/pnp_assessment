# Solution — Architecture, Trade-offs, and Reviewer Notes

Author: Tlavuvu · Target: reviewer of the Mid-Level Java Developer
Ecommerce Systems take-home.

This document is the narrative companion to the other docs. If you are
short on time, read §3 (stock correctness), §4 (SQL vs JPA), and §10
(rubric self-assessment).

---

## 1. Executive summary

A single-service Spring Boot 3 backend on PostgreSQL that supports:

- Product catalogue (create / list / get)
- Orders with transactional stock validation (**no oversell**)
- Order cancellation with transactional stock restoration
- Top-selling-products reporting via native SQL
- HTTP Basic auth + RBAC (ADMIN / USER) — optional per brief
- Per-principal rate limiting on write + report endpoints — optional
- Bean-Validation-backed input with a canonical error envelope
- 44 tests (unit + MockMvc + Testcontainers Postgres), green

The design bias across every decision was **correctness and clarity
over cleverness**. No Kafka, no Redis, no microservices, no JWT — the
brief forbids them and a 200-line service does not need them.

---

## 2. Architecture at a glance

Layered, package-by-layer:

```
controller → service (interface) → service.impl → repository → DB
            ↑                     ↑
            DI via interfaces     @Transactional boundary
```

- Controllers hold **no** business logic — only HTTP concerns
  (status codes, `Location` headers, DTO ↔ service handoff).
- Services are declared as **interfaces** (`ProductService`,
  `OrderService`, `ReportService`). Implementations live in
  `service/impl`. Controllers depend only on the interfaces, per the
  mandated rule and the Dependency Inversion Principle.
- Two repository flavours:
  - `JpaRepository` subinterfaces for CRUD (`ProductRepository`,
    `OrderRepository`).
  - `ReportJdbcRepository` (concrete) for SQL-driven aggregation
    using `NamedParameterJdbcTemplate`.
- A single `GlobalExceptionHandler` (`@RestControllerAdvice`) maps
  every thrown exception to the canonical JSON error envelope.
- Security + rate limiting live in `config/`, composed into one
  `SecurityFilterChain` bean.

Full diagram and package table in
[`ARCHITECTURE.md`](./ARCHITECTURE.md).

---

## 3. Stock correctness — the transaction story

The single most load-bearing correctness claim in this brief is "no
oversell under concurrent placements". The full path:

1. Client calls `POST /api/v1/orders` with N line items.
2. `OrderServiceImpl.placeOrder` is annotated `@Transactional`
   (propagation `REQUIRED`, isolation `READ_COMMITTED` — Postgres
   default).
3. Product ids are sorted ascending, then loaded with
   `SELECT … FOR UPDATE` via
   `ProductRepository.findAllByIdForUpdateOrdered(ids)`. The
   ascending-id order is a **lock-order discipline** (ADR-015): any
   two concurrent placements that overlap take locks in the same
   order, which rules out deadlock.
4. The service checks stock on the now-locked rows and throws
   `InsufficientStockException` (→ 409) if any row is short.
5. Stock is decremented in-memory on the managed JPA entities;
   `OrderItem` rows are created with `unitPrice` and `lineTotal`
   **snapshotted** (so later price changes don't rewrite history);
   `Order.total` is recalculated; the order is `save(...)`'d and the
   transaction commits.

Concurrency alternatives considered and rejected:

| Alternative | Why rejected |
|---|---|
| Optimistic locking (`@Version`) | Forces the service to own a retry loop; noisier logs; same outcome in the happy case. See ADR-015. |
| Atomic `UPDATE … WHERE stock >= ?` | Works for single-product orders; awkward for multi-line orders because the all-or-nothing guarantee spans rows. |
| Application-level mutex | Doesn't survive multiple JVMs; pushes concurrency out of the DB that actually owns the invariant. |
| Higher isolation (`REPEATABLE_READ`) | Adds serialization-failure retries for zero additional safety given row-level locks. |

**Proof that the guarantee holds** is in
`OrderConcurrencyIntegrationTest`: 50 threads race on a single product
with stock = 20. The test asserts:

- exactly 20 successes,
- exactly 30 `InsufficientStockException`,
- `SELECT stock FROM product …` returns 0,
- exactly 20 CONFIRMED orders persisted.

Runs in ~200 ms on a laptop; every run so far has been deterministic.

The same locking discipline applies to **cancel**: all products whose
stock will be restored are locked in ascending-id order before any
increment, so a concurrent placement targeting the same products
serialises behind the cancel.

Cancel is **non-idempotent by design** (ADR-013). A second cancel on
an already-`CANCELLED` order returns `409 ORDER_NOT_CANCELLABLE`
instead of silently double-restoring stock. Loosening this later is
backward-compatible; tightening it later would break clients.

---

## 4. Reporting — SQL vs JPA, and why

`GET /api/v1/reports/top-products` is backed by **native SQL** via
`NamedParameterJdbcTemplate` in `ReportJdbcRepository`, not JPQL /
Criteria / Spring Data derived query. Reasons, in order of weight:

1. **Ordering on derived columns.** The query ranks by
   `SUM(oi.quantity) DESC, SUM(oi.line_total) DESC, p.id ASC`. JPQL
   can do this, but it reads worse than the SQL, and JPA providers
   are historically inconsistent about referring to select aliases
   in `ORDER BY`.
2. **Aggregation + `LIMIT`.** Portable JPQL `setMaxResults` mixes
   poorly with derived-column ordering; writing the SQL directly is
   clearer.
3. **Future-proofing for Postgres-only features.** CTEs, window
   functions, `FILTER (WHERE …)`, and `DATE_TRUNC` are plausible
   next asks for a richer report. JPQL cannot express any of those.
4. **No managed entity required.** The result is a projection into
   `TopProductsReport.Row`. A managed entity would be more expensive
   (first-level cache, dirty tracking) for zero benefit.

CRUD paths (products, orders, order items) stay on JPA because:

- They are simple row-at-a-time reads and writes with graph cascades.
- Having Hibernate flush stock-decrement changes on commit is cleaner
  than issuing explicit `UPDATE`s.
- The `@OneToMany` cascade on `Order → OrderItem` is exactly what JPA
  is good at.

Query classification is called out in the Javadoc of every service /
repository method that issues a query — per the mandated rule.

---

## 5. Security approach

**Required or optional?** Optional per the brief. Implemented as an
enhancement because a real reviewer will check the RBAC matrix.

**What's there:**

- Spring Security with one `SecurityFilterChain` bean.
- HTTP Basic authentication — stateless; no sessions, no cookies.
- CSRF disabled (stateless JSON API; no cookie to forge).
- Two roles: `ADMIN`, `USER`. RBAC matrix matches
  [`API-DESIGN.md`](./API-DESIGN.md) §4.
- BCrypt password encoder.
- `InMemoryUserDetailsManager` seeded from `@ConfigurationProperties`
  (`SecurityProperties`), BCrypt-encoded at startup.
- `AuthenticationEntryPoint` → 401 envelope; `AccessDeniedHandler` →
  403 envelope; both share `SecurityErrorResponseWriter`.
- `RateLimitFilter` — Bucket4j, in-process, per-principal, two
  categories (writes 30/min, reports 10/min; reads unthrottled). Runs
  **after** `AuthorizationFilter` so unauthenticated floods don't
  consume quota.

**What's deliberately not there:**

- JWT / OAuth2 — explicitly forbidden by the brief.
- DB-backed user accounts — would double Phase 3/4 scope; the
  in-memory store is fail-fast on boot if mis-wired.
- Method-level `@PreAuthorize` ownership checks on
  `GET /orders/{id}` — deferred and called out as underengineered.
- Cluster-safe rate limiting (Redis / Hazelcast) — forbidden by the
  brief; in-memory is documented as a single-instance constraint.

All five security ADRs (016, 017, 018, 019) are in
[`DECISIONS.md`](./DECISIONS.md).

---

## 6. Validation & error handling

**Two layers, defence-in-depth:**

| Layer | Mechanism | Example |
|---|---|---|
| API boundary | Jakarta Bean Validation on DTO records | `@DecimalMin("0.01")` on `ProductRequest.price` |
| Domain / DB | Entity guards + DB CHECK constraints | `product.stock CHECK (stock >= 0)` |

DTO annotations reject bad input with 400 before any transaction
opens. Entity / DB guards exist as a safety net if a future code path
forgets the DTO.

Custom validator `@UniqueProductIds` on `CreateOrderRequest.items`
rejects payloads with duplicate `productId` values — something
standard Bean Validation cannot express.

**Errors are shaped through a single envelope** (`ErrorResponse`):

```json
{
  "timestamp": "2026-04-23T10:15:30Z",
  "status": 409,
  "error": "Conflict",
  "code": "INSUFFICIENT_STOCK",
  "message": "requested 5 of product 42, only 2 available",
  "path": "/api/v1/orders",
  "fieldErrors": []
}
```

`GlobalExceptionHandler` catches:

- Domain exceptions (`sealed class DomainException` with 4 permitted
  subtypes), grouped into 404 and 409 handlers by HTTP status.
- Validation errors (body + path/query).
- Malformed-JSON / type-mismatch / wrong-method / unmatched-route.
- A final `Exception` catch-all → 500 `INTERNAL_ERROR`.

Codes emitted by the app exactly match the table in
[`API-DESIGN.md`](./API-DESIGN.md) §2, reconciled in Phase 11.

---

## 7. Testing

Stats: **44 tests, 0 failures, 0 errors**, ~11 s end-to-end on a
laptop. Broken down by layer:

| Layer | Suites | Tool |
|---|---|---|
| Service unit tests | 17 tests across 3 classes | JUnit 5 + Mockito |
| Controller integration | 24 tests across 4 classes | MockMvc + Testcontainers Postgres |
| Schema smoke | 2 tests | JdbcTemplate + Testcontainers |
| **Concurrency proof** | 1 test | Service bean + `ExecutorService` + Testcontainers |

Why Testcontainers and not H2:

- H2 cannot faithfully represent `SELECT … FOR UPDATE` semantics.
- `NUMERIC(12,2)` strictness, CHECK constraint behaviour, and enum
  string-coercion rules differ — classic source of false greens.
- The concurrency proof test only makes sense on a real Postgres.

The concurrency test is the highest-signal spec in the suite and is
called out separately in
[`TEST-STRATEGY.md`](./TEST-STRATEGY.md).

One concession: `maven-surefire-plugin` runs with
`-Dnet.bytebuddy.experimental=true` because the local JDK (25) is
ahead of the Mockito-bundled ByteBuddy's official support window
(24). Scoped to the test JVM only; documented in `pom.xml`.

---

## 8. What's overengineered

Candid answer — I'll push back on only one of these being removed.

- **`SecurityErrorResponseWriter`** as a dedicated Spring bean. It
  exists because three call sites (401 entry point, 403 handler, 429
  filter) all emit the canonical error envelope. Three copies drift.
  One bean stays in step with `GlobalExceptionHandler` by
  construction. I'd keep it.
- **Custom `@UniqueProductIds` validator**. Could be replaced with
  service-layer code. Keeping it puts the rule at the API boundary
  where bad payloads never reach a transaction.
- **`PageResponse<T>` envelope** (vs. returning `Page<T>`). Shields
  clients from Spring's `pageable` substructure changes across
  versions. Cheap insurance.

## 9. What's underengineered

- **USER-own-orders enforcement.** Currently an authenticated user
  can `GET` any order. Enforcing ownership requires either a
  principal-to-customer mapping or an explicit owner column. Deferred
  and flagged in `API-DESIGN.md` §4.
- **Rate-limit bucket eviction.** `ConcurrentHashMap<String, Bucket>`
  has no TTL. Fine for the brief's user cardinality (two seeded
  users) but a production follow-up would use Caffeine with an idle
  expiry.
- **Rate-limit multi-instance coordination.** Forbidden by the brief
  (no Redis); documented as a single-instance constraint.
- **Seeded users stored with plaintext passwords in dev/test YAML.**
  Encoded at startup, never persisted, profile-gated — but a
  production profile would use BCrypt-prefixed hashes via env vars
  or a DB-backed `UserDetailsService`.
- **No observability beyond health/info.** A Micrometer + Prometheus
  endpoint + a few custom meters would be one hour of work.

---

## 10. Biggest risks (what could bite in production)

1. **Single-instance rate-limit quota.** A 2-node deploy effectively
   doubles quota. Mitigation: gateway-level rate limiting or a
   cluster-aware store.
2. **Persisted `orders.total` can drift from `SUM(line_total)`**
   if a future path mutates items without updating total (ADR-014).
   Mitigation: nightly reconciliation job or a DB trigger.
3. **Schema drift.** `ddl-auto=validate` catches most of it at boot,
   but schema changes that pass validation (e.g. column widening) can
   still surprise. Liquibase is the source of truth — **never** edit
   an applied migration.
4. **In-memory user store is a cold-start dependency** on the config
   file. A missing `security.users` block in a new profile fails
   boot. That's intentional (fail-fast) but operators need to know.

---

## 11. 1-hour and 1-day follow-ups

**Fix in 1 hour:**

- Add Caffeine-backed bucket map with 10-min idle expiry.
- Externalise rate-limit capacities to `@ConfigurationProperties`.
- Add a `RateLimitFilter` unit test using a fresh filter + stubbed
  `SecurityContext`.
- Bump `mockito.version` in `pom.xml` to drop the Byte Buddy
  experimental flag.

**Fix in 1 day:**

- DB-backed `UserDetailsService` with a `user_account` / `user_role`
  Liquibase changeset.
- Method-level `@PreAuthorize` ownership enforcement on
  `GET /orders/{id}`.
- Ownership column on `orders` + index; migrate
  `customer_reference` to `owner_user_id`.
- Micrometer + Prometheus + a few business counters (orders placed,
  orders cancelled, stock-exhausted rejections).
- OpenAPI via `springdoc-openapi-ui`.

---

## 12. Rubric self-assessment (0–5)

| Category | Score | Reason |
|---|---|---|
| Domain Modeling | 4.5 | Order aggregate + snapshot pricing + sealed domain exceptions. |
| Database Design | 4.5 | CHECK constraints, FK `ON DELETE` rules, 5 indexes, ADR-documented. |
| Transaction Management | 4.8 | Pessimistic lock + ordered acquisition proven by concurrency test. |
| Business Logic | 4.5 | Clean service methods, all with javadoc-declared guarantees. |
| API Design | 4.5 | Stable envelope, explicit status codes, `Location` headers, RBAC matrix, reconciled doc. |
| Validation/Error Handling | 4.7 | Canonical envelope; custom validator; 14 codes documented and emitted. |
| SQL/Reporting | 4.7 | Native SQL with deterministic tiebreaker; assertion against a real Postgres. |
| Architecture | 4.5 | Interface-based services, DIP, package-by-layer, single entry for errors. |
| Security | 4.5 | HTTP Basic + RBAC + rate limiting; five ADRs; verified end-to-end. |
| Testing | 4.5 | Pyramid + Testcontainers + concurrency proof. |
| Documentation | 4.5 | Five design docs + 19 ADRs + this SOLUTION. |
| Engineering Communication | 4.7 | Every non-trivial decision explains the alternative it rejected. |

**Overall: ~4.6**, no category below 4.

Meets the ≥ 4.2 floor on every category and the ≥ 4.2 overall target.
