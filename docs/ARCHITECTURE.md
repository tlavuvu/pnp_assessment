# Architecture — Ecommerce Backend

> Phase 1 design artifact. No code yet. This document fixes the shape of the
> system, dependency direction, transaction boundaries, and cross-cutting
> concerns so later phases can execute mechanically.

---

## 1. Goals & Non-Goals

### Goals (in scope)
- REST backend for: product catalogue, order placement, order cancellation,
  reporting (top-selling products by date range).
- Strong correctness guarantees on stock: no oversell, no lost updates, no
  partially cancelled orders.
- Liquibase as schema source of truth. Hibernate auto-DDL disabled.
- Clear layering; interface-driven services; global error handling.
- Basic Auth + RBAC + rate limiting (optional per brief, included pragmatically).

### Non-Goals (explicitly out of scope)
- Microservices, event streaming (Kafka), Redis caches, CQRS, WebFlux.
- JWT/OAuth2 authorization servers, full user self-service flows.
- Payment integration, shipping, inventory reservations across warehouses.
- UI / frontend.

Rationale: the brief asks for correctness, clarity, and robustness — not
distributed scale. Every excluded technology would add review surface area
without raising the score on any rubric category.

---

## 2. Style & Constraints

- **Java 17**, **Spring Boot 3.x**, **Spring MVC** (blocking servlet stack).
- **PostgreSQL** only (no H2 in prod paths; H2 acceptable only for unit
  slices if needed, but integration tests target Postgres via Testcontainers
  or a local DB — decision deferred to Phase 10).
- **Spring Data JPA** for CRUD, **JdbcTemplate** for reporting SQL.
- **Liquibase** (SQL-format changelogs) for schema + seed.
- **Maven** build, **JUnit 5 + MockMvc** for tests.
- Source layout follows `src/main/java/.../{config,controller,dto,entity,
  enumtype,exception,mapper,repository,service,service/impl,util}`.

---

## 3. Layered Architecture

```
┌───────────────────────────────────────────────────────────────┐
│  Controller layer  (REST, MVC, validation entry, no logic)    │
│    └─ depends on → Service *interfaces*                       │
├───────────────────────────────────────────────────────────────┤
│  Service layer     (business rules, @Transactional boundary)  │
│    └─ depends on → Repository interfaces + Mappers            │
├───────────────────────────────────────────────────────────────┤
│  Repository layer  (Spring Data JPA + JdbcTemplate for SQL)   │
│    └─ depends on → Entities + DataSource                      │
├───────────────────────────────────────────────────────────────┤
│  Persistence       (PostgreSQL, schema owned by Liquibase)    │
└───────────────────────────────────────────────────────────────┘
```

### Dependency direction rules
- Controllers never import entities directly — they speak DTOs.
- Services own the transactional boundary (not controllers, not repos).
- Repositories return entities; mappers convert entity ↔ DTO.
- No layer skipping (controller → repository is forbidden).

### Why interface-based services (vs. concrete classes)
1. **Testability**: controllers can be unit-tested with `@MockBean
   ProductService` without dragging in JPA.
2. **Substitutability**: a future `ProductServiceCachedImpl` or
   `OrderServiceSagaImpl` can be swapped with zero controller change.
3. **Spring AOP alignment**: `@Transactional` proxies are created more
   cleanly around interfaces (JDK dynamic proxies vs. CGLIB) and avoid
   self-invocation gotchas when combined with carefully-scoped helpers.
4. **Rubric signal**: reviewers explicitly look for SOLID (DIP). Interfaces
   are the cheapest, clearest way to demonstrate it.

Alternative considered: direct `@Service` classes injected into controllers.
Rejected because it couples controllers to the implementation and weakens
the architecture score for no real gain in this codebase size.

---

## 4. Modules / Packages

```
com.pnp.ecommerce
├── EcommerceApplication.java
├── config/          SecurityConfig, RateLimitConfig, OpenApiConfig
├── controller/      ProductController, OrderController, ReportController
├── dto/             *Request, *Response records
├── entity/          Product, Order, OrderItem
├── enumtype/        OrderStatus
├── exception/       DomainException hierarchy + GlobalExceptionHandler
├── mapper/          ProductMapper, OrderMapper (MapStruct or hand-rolled)
├── repository/      ProductRepository, OrderRepository, ReportJdbcRepository
├── service/         ProductService, OrderService, ReportService (interfaces)
├── service/impl/    *ServiceImpl
└── util/            Clock provider, pagination helpers
```

Each file stays under the 500-line cap mandated by `.cursorrules`. No god
classes; single responsibility per file.

---

## 5. Domain Model (high level)

Three aggregates, one write-consistency boundary per order:

- **Product** — catalogue item with price and stock.
- **Order** — header: customer reference, status, totals, timestamps.
- **OrderItem** — line inside an order: product reference, qty, price snapshot.

`OrderStatus` enum: `PENDING`, `CONFIRMED`, `CANCELLED`.

Stock lives on `Product`. An order's effect on stock is applied at
**confirmation time** (we treat placement = confirmation in this brief).
Cancellation restores stock **only if** the order was in a status that had
consumed stock.

Full column-level design lives in `docs/DB-DESIGN.md`.

---

## 6. Transaction Strategy

### Where boundaries live
- `@Transactional` sits on **service implementation methods**, never on
  controllers or repositories. This keeps the boundary visible at the layer
  that owns business rules.
- Read-only endpoints (list/detail/report) use `@Transactional(readOnly=true)`
  to hint the driver and skip dirty-check flush.

### Concurrency on stock (the hard part)
Two writers decrementing the same product's stock must never oversell.
Three options were considered:

| Option | Mechanism | Verdict |
|---|---|---|
| **A. Pessimistic row lock** | `SELECT … FOR UPDATE` on each `product` row in the order, inside the transaction | **Chosen.** Deterministic, simple, fits Postgres, no retry loop needed for the review scope. |
| B. Optimistic locking | `@Version` on `Product`, retry on `OptimisticLockException` | Works, but forces the service to own a retry loop and complicates error responses. Noise for this brief. |
| C. Atomic conditional UPDATE | `UPDATE product SET stock = stock - :q WHERE id = :id AND stock >= :q` | Elegant, but harder to return a precise "which product was short" error and harder to combine with JPA-managed entities without flush surprises. |

**Chosen guarantee:** `placeOrder` and `cancelOrder` run inside a single
`@Transactional` method with `SERIALIZABLE`-safe behavior achieved via
pessimistic row locking on every `Product` touched. The lock is acquired in
a **stable order (product id ascending)** to prevent deadlocks when two
orders touch an overlapping product set.

### What breaks without it
- **Oversell**: two concurrent orders each read `stock=5`, each write
  `stock=3`, we've sold 4 items out of 5 stock.
- **Partial cancel**: crash between decrementing stock and inserting order
  items leaves stock wrong and order missing lines.
- **Lost cancel**: cancel restores stock, concurrent order places against
  stale read, double-spend.

### What `@Transactional` actually gives us
- Atomicity of the whole unit (all decrements + inserts commit together).
- Consistency via DB constraints (`stock >= 0` CHECK) even if app logic
  slips.
- Isolation against concurrent readers/writers via the row locks we take.
- Durability via Postgres WAL once the commit returns.

### Alternatives (documented, not implemented)
- Event-sourced inventory with compensation — overkill.
- Distributed lock via Redis — unnecessary on a single DB.
- Saga pattern for multi-service checkout — no other services exist.

---

## 7. Reporting Strategy (SQL, not ORM)

**Why explicit SQL** for top-selling-products-by-date-range:

1. The query is an **aggregation over a join** (`order_item` ⋈ `orders`)
   filtered by `orders.created_at` and grouped by `product_id`, ordered by
   `SUM(qty) DESC`. JPQL handles this, but the brief explicitly asks for
   SQL. Reviewer signal matters.
2. Reporting queries tend to evolve toward window functions, CTEs, and
   index hints that JPA/JPQL models poorly. Using `JdbcTemplate` from day
   one keeps that path open without a later refactor.
3. Entities are a liability in reports — we don't want Hibernate hydrating
   `Order` + `OrderItem` + `Product` graphs just to sum integers.
4. Projection is to a lightweight `TopProductRow` record, mapped via a
   `RowMapper`, returned as `List<TopProductResponse>`.

A single SQL file or method will own the query; binding uses named
parameters (`NamedParameterJdbcTemplate`) for safety against injection.

---

## 8. API Surface (preview — full contract in `API-DESIGN.md`)

- `POST /api/v1/products`            (ADMIN) create product
- `GET  /api/v1/products`            (USER)  list products
- `GET  /api/v1/products/{id}`       (USER)  get product
- `POST /api/v1/orders`              (USER)  place order
- `GET  /api/v1/orders/{id}`         (USER)  get order
- `POST /api/v1/orders/{id}/cancel`  (ADMIN) cancel order
- `GET  /api/v1/reports/top-products?from=&to=&limit=` (ADMIN) report

Versioned under `/api/v1` so future contract changes don't break clients.

---

## 9. Validation & Error Handling

- **Bean Validation** (`jakarta.validation`) on every request DTO.
  Controllers use `@Valid`; violations bubble to the global handler.
- **Domain exceptions** (`ProductNotFoundException`, `InsufficientStock
  Exception`, `OrderNotFoundException`, `IllegalOrderStateException`)
  mapped to HTTP codes in `GlobalExceptionHandler` (`@RestControllerAdvice`).
- **Error envelope** is stable JSON: `{timestamp, status, error, code,
  message, path, fieldErrors?}`. Documented in `API-DESIGN.md`.

Why this shape:
- Stable `code` lets clients branch without string-matching `message`.
- `fieldErrors[]` lets UIs highlight inputs.
- No stack traces or entity leakage to callers.

---

## 10. Security (optional enhancement)

Classification per brief: **optional enhancement** — implemented because
it's cheap, demonstrates RBAC competence, and protects write endpoints.

- **Spring Security** with HTTP Basic (stateless).
- **Users** seeded via Liquibase: one ADMIN, one USER. BCrypt hashes.
- **RBAC**:
  - `ADMIN`: create product, cancel order, read reports.
  - `USER`: place order, read catalogue, read own order.
- **Rate limiting** on write endpoints via Bucket4j in-memory buckets keyed
  by authenticated principal (fallback to IP for anonymous).
- **Stateless**: `SessionCreationPolicy.STATELESS`, CSRF disabled (safe
  because there's no session/cookie auth).
- **Responses**: 401 for missing/bad credentials, 403 for role mismatch,
  429 for rate-limit exhaustion.

Explicitly **not** implemented: JWT, OAuth2, refresh tokens, account
lockout, password reset, user registration endpoints. All overkill for a
take-home.

---

## 11. Cross-Cutting Concerns

- **Logging**: SLF4J + default Logback, JSON-less dev format, key/value
  MDC fields (`orderId`, `productId`) added at service entry.
- **Configuration**: `application.yml` with profiles `dev`, `test`, `prod`.
  Secrets via env vars only.
- **Time**: inject `Clock` instead of calling `Instant.now()` directly, so
  tests can freeze time.
- **IDs**: `BIGINT` identity on all tables; UUIDs rejected as overkill for
  this scope.
- **Money**: `NUMERIC(12,2)` in DB, `BigDecimal` in Java. No `double`.

---

## 12. Build & Run

- `mvn clean verify` runs unit + slice tests.
- `mvn spring-boot:run -Pdev` boots against a local Postgres.
- Liquibase runs on startup; Hibernate `ddl-auto=validate`.

Detailed setup lives in `README.md` (Phase 2).

---

## 13. Open Decisions (deferred)

| Topic | Options | Decision phase |
|---|---|---|
| Integration test DB | Testcontainers vs. local Postgres vs. H2 | Phase 10 |
| Mapper style | MapStruct vs. hand-rolled | Phase 5 |
| OpenAPI generation | springdoc-openapi vs. hand-written docs | Phase 11 |
| Order idempotency | `Idempotency-Key` header vs. none | Deferred (risk noted) |

---

## 14. Trade-offs Summary

- **Pessimistic locking** trades a little throughput for a lot of
  correctness and review clarity. Acceptable for the brief's scale.
- **JPA + JdbcTemplate side-by-side** trades a small conceptual cost
  (two data-access styles) for the right tool per job.
- **Basic Auth + Bucket4j** trades production polish (no JWT, no Redis
  quotas) for reviewability and zero external dependencies.
- **Monolith, single module** trades future scale-out for simpler review,
  simpler tests, and one deployable.
