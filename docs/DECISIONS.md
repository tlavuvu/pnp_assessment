# Decisions Log (ADR-lite)

> One entry per non-trivial decision. Each entry states the choice, the
> alternative rejected, and why. Entries are immutable once merged;
> reversals append a new entry and reference the old one.

---

## ADR-001 — Spring Boot 3.4.13 + Java 17

- **Date**: Phase 2
- **Status**: Accepted
- **Context**: Brief mandates Java 17 and Spring Boot 3.x.
- **Decision**: `spring-boot-starter-parent:3.4.13` with Java 17 toolchain.
- **Alternative considered**: 3.5.x (newer) or 3.3.x (older LTS-ish).
- **Why**: 3.4.13 is the latest patch on a line that has had months of
  production use; 3.5.x still sees monthly patches. For a take-home we
  optimise for "boots cleanly on the reviewer's box", not latest-features.

## ADR-002 — `spring-boot-starter-parent` over BOM import

- **Date**: Phase 2
- **Status**: Accepted
- **Decision**: Inherit from `spring-boot-starter-parent`.
- **Alternative**: `spring-boot-dependencies` BOM + manual plugin management.
- **Why**: No corporate parent to preserve; starter-parent is strictly
  less configuration for the same outcome and also manages the
  `spring-boot-maven-plugin` automatically.

## ADR-003 — PostgreSQL for all environments (no H2)

- **Date**: Phase 2
- **Status**: Accepted
- **Decision**: Postgres in dev, prod, and tests (via Testcontainers).
- **Alternative**: H2 in tests.
- **Why**: The correctness story hinges on `SELECT … FOR UPDATE`, strict
  `NUMERIC`, and Postgres-strict CHECK semantics. H2 silently passes
  queries that Postgres rejects and cannot reproduce row-level locks,
  which would make concurrency tests meaningless.

## ADR-004 — Testcontainers via the `jdbc:tc:` URL driver

- **Date**: Phase 2
- **Status**: Accepted
- **Decision**: `jdbc:tc:postgresql:16-alpine:///pnp_ecommerce_test` in
  `application-test.yml`, no `@Testcontainers` boilerplate.
- **Alternative**: `@Testcontainers` + `@Container` static fields per test.
- **Why**: Zero wiring in test classes; the JVM auto-starts one container
  per unique URL and reuses it across tests in the same run. Trade-off:
  slightly less control over lifecycle; acceptable at this scope.

## ADR-005 — Liquibase as schema source of truth; Hibernate `ddl-auto=validate`

- **Date**: Phase 2 (enforced), Phase 3 (executed)
- **Status**: Accepted
- **Decision**: All schema + seed shipped via SQL-formatted Liquibase
  changelogs. Hibernate only validates at boot.
- **Alternative**: `ddl-auto=update` or `create-drop`.
- **Why**: `update` quietly does the wrong thing under drift (it happily
  adds columns but never drops them, and won't manage CHECK constraints
  or indexes). Reviewers grade migration hygiene — Liquibase makes that
  visible and reversible.

## ADR-006 — Spring Data JPA for CRUD, `NamedParameterJdbcTemplate` for reporting

- **Date**: Phase 2 (dependency), Phase 8 (usage)
- **Status**: Accepted
- **Decision**: Two data-access styles, each at the right level.
- **Alternative**: JPA-only (JPQL for reporting).
- **Why**: JPA gives free CRUD with minimal boilerplate; aggregation
  queries benefit from native SQL (index alignment, window functions
  later, no entity hydration). Brief explicitly asks for SQL in reports.

## ADR-007 — Bucket4j `8.10.1` on coordinate `com.bucket4j:bucket4j-core`

- **Date**: Phase 2
- **Status**: Accepted
- **Decision**: Pin Bucket4j to `8.10.1`.
- **Alternative**: Bucket4j `8.14+` (artifact renamed to
  `bucket4j_jdk17-core`).
- **Why**: `8.10.1` is the last release on the simple `bucket4j-core`
  coordinate before the JDK-tagged artifact split. Fewer surprises for
  reviewers; functionally sufficient for in-memory rate limiting.
  Classification: **optional enhancement** per brief.

## ADR-008 — `SecurityFilterChain` with HTTP Basic + stateless session

- **Date**: Phase 2 (dependency), Phase 9 (executed)
- **Status**: Accepted
- **Decision**: HTTP Basic, BCrypt users seeded via Liquibase, stateless.
- **Alternative**: JWT/OAuth2.
- **Why**: Brief classifies security as **optional**. Basic auth is the
  smallest control that demonstrates RBAC, with zero external
  infrastructure. JWT/OAuth2 would be pure scope creep.
  **Classification**: optional enhancement.

## ADR-009 — Package by layer (`controller`, `service`, `repository`, …)

- **Date**: Phase 2
- **Status**: Accepted
- **Decision**: Flat layered packages under `com.pnp.ecommerce`.
- **Alternative**: Package-by-feature (`product`, `order`, `report`).
- **Why**: The brief lists distinct architectural layers in its rubric.
  Package-by-layer makes each layer's files trivial to find for a
  reviewer. At this scale (≤ 3 aggregates) the usual downside of
  layered packaging — cross-package coupling — doesn't bite.

## ADR-010 — Interface-based services (`*Service` + `*ServiceImpl`)

- **Date**: Phase 2 (enforced), Phase 6 (executed)
- **Status**: Accepted
- **Decision**: Every service has an interface; controllers depend only
  on interfaces.
- **Alternative**: Concrete `@Service` classes injected directly.
- **Why**: DIP, cleaner JDK-dynamic-proxy boundary for `@Transactional`,
  easier `@MockBean` in slice tests. Rubric signals SOLID.

## ADR-011 — `Location` header on POST, not just response body

- **Date**: Phase 2 (contract), Phase 7 (executed)
- **Status**: Accepted
- **Decision**: `POST /products` and `POST /orders` return `201 Created`
  with `Location: /api/v1/…/{id}` and the full response body.
- **Alternative**: Return id only and make the client reconstruct URLs.
- **Why**: Standard REST semantics and cheaper clients.

## ADR-012 — Money as `NUMERIC(12,2)` / `BigDecimal`

- **Date**: Phase 2 (contract), Phase 3 (schema)
- **Status**: Accepted
- **Decision**: `NUMERIC(12,2)` in Postgres, `BigDecimal` in Java.
- **Alternative**: `DOUBLE PRECISION` / `double`.
- **Why**: Floating-point money is a landmine. Scale 2 is sufficient for
  ZAR/EUR/USD in this brief. Max value ~9.99 billion — plenty.

## ADR-013 — Cancel is **not** idempotent (CANCELLED → cancel → 409)

- **Date**: Phase 1 (contract), Phase 6 (executed)
- **Status**: Accepted
- **Decision**: Second cancel on an already-cancelled order returns `409
  ILLEGAL_ORDER_STATE`.
- **Alternative**: Silent `204` (idempotent).
- **Why**: Loosening later is backward-compatible; tightening later
  breaks clients. Strictness also surfaces client bugs earlier.
  Logged as a known trade-off; reversible if the reviewer prefers idempotent.

## ADR-014 — Derived `orders.total` persisted (not recomputed on read)

- **Date**: Phase 1 (contract), Phase 3 (schema)
- **Status**: Accepted with noted risk
- **Decision**: Store `orders.total = SUM(line_total)` at write time.
- **Alternative**: Always compute from `order_item` on read.
- **Why**: Reports stay cheap; payloads return totals without a join.
  **Risk**: drift if a code path updates lines without updating total.
  Mitigated by keeping line/order mutations inside a single service
  method; a nightly reconciliation job or DB trigger could harden this
  later (deferred).

## ADR-015 — Deadlock avoidance by lock-order discipline

- **Date**: Phase 1 (contract), Phase 6 (executed)
- **Status**: Accepted
- **Decision**: When an order touches multiple products, always acquire
  `SELECT … FOR UPDATE` in ascending `product.id` order.
- **Alternative**: No ordering; rely on Postgres deadlock detection +
  retry.
- **Why**: Retry loops are easy to get wrong and noisy in logs. A
  deterministic lock order is free correctness.

## ADR-016 — HTTP Basic + in-memory users (no JWT, no DB user schema)

- **Date**: Phase 9
- **Status**: Accepted (optional enhancement per brief)
- **Decision**: Spring Security with HTTP Basic, stateless sessions,
  users seeded from `@ConfigurationProperties` into
  `InMemoryUserDetailsManager`.
- **Alternatives**:
  1. **JWT / OAuth2** — explicitly forbidden by the brief.
  2. **DB-backed `UserDetailsService` + user-account schema** —
     doubles Phase 3 + Phase 4 scope for zero extra reviewer signal
     on the actual assignment (catalogue, orders, reporting).
- **Why**: Basic-Auth is the smallest authenticator that actually
  enforces the RBAC matrix in `API-DESIGN.md` §4. Stateless +
  CSRF-disabled matches a JSON API's threat model (no cookie forgery
  surface). The trade-off is documented rather than silently skipped.

## ADR-017 — Plaintext seed passwords in dev/test, BCrypt-encoded at startup

- **Date**: Phase 9
- **Status**: Accepted
- **Decision**: `application-{dev,test}.yml` hold plaintext passwords;
  `SecurityConfig` encodes them with BCrypt on boot before building
  `UserDetails`. Base `application.yml` has **no** `security.users`
  block, so booting without a dev/test/prod profile fails fast
  (missing required binding).
- **Alternatives**:
  1. **Hashes in config** — inconvenient to rotate locally; still
     unsafe to commit.
  2. **Random password logged at startup** (Spring default) — breaks
     reproducible integration tests.
- **Why**: Fail-fast in non-dev profiles + zero-friction local auth.
  A prod profile would override with a BCrypt-prefixed-encoder + env
  vars, or swap `UserDetailsService` for a DB-backed implementation.

## ADR-018 — In-process Bucket4j buckets per principal (no Redis / Hazelcast)

- **Date**: Phase 9
- **Status**: Accepted with documented risk
- **Decision**: `RateLimitFilter` keeps a `ConcurrentHashMap<String,
  Bucket>` keyed by `principal + category` (WRITE or REPORT).
- **Alternatives**:
  1. **Redis / Hazelcast-backed buckets** — explicitly forbidden (no
     Redis in stack).
  2. **Gateway-level rate limiting** — out of scope; there is no
     gateway in the brief.
- **Why**: The brief assumes a single-instance deploy. An N-instance
  deploy would multiply the effective quota by N — a known limitation
  and the first thing a production-grade follow-up would address.
  Bucket4j was also chosen over a hand-rolled sliding-window counter
  because "greedy" refill + thread-safety are correctness-sensitive,
  and Bucket4j already implements both.

## ADR-019 — RateLimitFilter runs after `AuthorizationFilter`

- **Date**: Phase 9
- **Status**: Accepted
- **Decision**: `addFilterAfter(rateLimitFilter, AuthorizationFilter.class)`.
- **Alternative**: Rate-limit before authentication (`addFilterBefore(…,
  BasicAuthenticationFilter.class)`).
- **Why**: The bucket is keyed on the authenticated principal. Running
  after authorization means:
  - unauthenticated traffic is 401'd without spending tokens,
  - authenticated-but-forbidden traffic is 403'd without spending
    tokens,
  - only traffic that would actually be processed consumes quota.
  The downside is that an unauthenticated flood can still hit the
  auth code path; mitigated at the infra layer in production.
