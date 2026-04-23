# Test Strategy — Ecommerce Backend

> Phase 1 artifact. Defines what we test, at which layer, with which tools,
> and with which fixtures — before any production code is written. Phase 10
> executes this plan.

---

## 1. Pyramid

```
             ┌──────────────────────┐
             │  End-to-end (few)    │   not in scope for brief
             ├──────────────────────┤
             │  Integration slices  │   @SpringBootTest + MockMvc + DB
             ├──────────────────────┤
             │   Unit tests (many)  │   services + mappers + validators
             └──────────────────────┘
```

- **Unit**: pure JUnit 5, Mockito for collaborators. Fast, no Spring
  context. Cover services, mappers, domain rules, error mapping.
- **Integration slices**: load the minimum Spring context needed:
  - `@WebMvcTest` for controller wiring (service mocked).
  - `@DataJpaTest` for repository queries against a real DB.
  - `@SpringBootTest(webEnvironment=RANDOM_PORT)` for full-stack order
    flow incl. Liquibase, security, rate limit.

---

## 2. Tooling

| Need | Choice | Why |
|---|---|---|
| Test runner | JUnit 5 (Jupiter) | Standard. |
| Mocking | Mockito (+ `mockito-inline` if needed) | Ships with `spring-boot-starter-test`. |
| Assertions | AssertJ | Fluent, Spring default. |
| HTTP | MockMvc | Right level for controller slice + full-stack tests. |
| DB (integration) | Testcontainers Postgres (preferred) OR local Postgres | Matches production dialect — H2 can't represent `FOR UPDATE`, `NUMERIC`, or CHECKs faithfully. Final choice deferred to Phase 10. |
| JSON | Spring Boot's Jackson + `JsonPath` matchers | — |
| Time | Inject a `Clock` bean; override with `Clock.fixed(...)` in tests | Deterministic reports. |

**H2 is explicitly rejected for integration tests** of stock concurrency
and reporting SQL — it would silently pass queries that Postgres rejects
and cannot reproduce row-level locks.

---

## 3. Coverage Goals

Not a raw percentage goal — coverage shaped by behavior:

- **≥ 90%** on services (business logic, transactional code, error paths).
- **100%** on `GlobalExceptionHandler` mappings.
- **≥ 80%** on controllers (happy + one failure per endpoint).
- Reporting SQL: at least one integration test asserting ordering and
  the date-range half-open `[from, to)` boundary.
- Security: positive test per role + 401 + 403 + rate-limit 429.

---

## 4. Test Catalogue (what we will write)

### 4.1 Product flow
- `ProductServiceImplTest` (unit)
  - creates product with valid input
  - rejects negative price / stock (relies on validator + service guard)
  - findById returns `ProductResponse`; not-found throws domain exception
- `ProductControllerTest` (@WebMvcTest)
  - 201 with Location header on create
  - 400 on invalid payload, stable error envelope
  - 401 anonymous, 403 USER on create (ADMIN-only)

### 4.2 Order placement — happy paths
- `OrderServiceImplTest` (unit)
  - single-item order: decrements stock, snapshots price, totals correct
  - multi-item order: lines ordered stably, total = Σ line_total
- `OrderPlacementIntegrationTest` (@SpringBootTest)
  - places a real order, asserts DB state (stock decremented, items rows
    present, total persisted equals sum)
  - verifies `Location` header points to `GET /orders/{id}` and it returns
    the same body shape

### 4.3 Order placement — failure paths
- missing product → 404 `PRODUCT_NOT_FOUND`, stock unchanged
- insufficient stock on one of several lines → 409 `INSUFFICIENT_STOCK`,
  **no partial write** (transaction rollback verified by asserting stock
  unchanged for all products in the order)
- duplicate `productId` in items → 400 `VALIDATION_FAILED`
- quantity ≤ 0 → 400 `VALIDATION_FAILED`
- empty items array → 400 `VALIDATION_FAILED`

### 4.4 Concurrency test (stretch goal)
Two threads place orders for the same product where combined stock
exceeds supply. Expect exactly one success, one 409, and final stock ≥ 0.
Implemented with `CountDownLatch` or `Executors.newFixedThreadPool(2)`
against a Testcontainers Postgres. Documented as a stretch goal because
flaky concurrency tests are worse than no test; if it can't be made
deterministic, downgrade to a lock-acquisition-order unit assertion on
the repository layer.

### 4.5 Order cancellation
- `OrderCancellationTest` (@SpringBootTest)
  - CONFIRMED → cancel → 204, status=CANCELLED, stock restored per item
  - CANCELLED → cancel → 409 `ILLEGAL_ORDER_STATE`
  - non-existent order → 404 `ORDER_NOT_FOUND`
  - USER role forbidden → 403

### 4.6 Reporting
- `ReportRepositoryTest` (@DataJpaTest + JdbcTemplate, real Postgres)
  - fixtures: 3 products × several orders in and out of range,
    one CANCELLED order, one with boundary timestamps
  - asserts ordering (units desc, revenue desc as tiebreaker)
  - asserts half-open range: order at `to` is excluded, at `from` included
  - asserts CANCELLED orders excluded
  - asserts `limit` respected
- `ReportControllerTest`
  - 400 when `to <= from`
  - 400 when range > 366 days
  - 401/403 coverage

### 4.7 Security & rate limiting
- 401 on anonymous access to any endpoint
- 403 on USER hitting ADMIN-only endpoints
- 200 on correct role + correct credentials
- 429 after N writes within window; `Retry-After` header present

### 4.8 Validation & error envelope
- Snapshot-style assertion that every error response contains the fixed
  fields `{timestamp, status, error, code, message, path}` and nothing
  leaks (no stack trace, no entity fields).

---

## 5. Fixtures & Data Builders

- Test data created via small builders (e.g. `ProductBuilder.aProduct()
  .withStock(5).build()`). Reason: explicit intent at call site, resilient
  to entity changes, reads like domain language.
- No shared mutable fixtures across tests. Each test seeds what it needs.
- Liquibase runs per integration context; tests clean up inside
  `@Transactional` rollback or by truncating between tests.

---

## 6. Deterministic Time

- Inject a `Clock` bean (`ClockConfig`).
- Production: `Clock.systemUTC()`.
- Tests: `Clock.fixed(Instant.parse("2026-04-23T10:15:30Z"), UTC)`.
- All entities populate `created_at` / `updated_at` from this clock, not
  from `Instant.now()` inline. Makes report date-range assertions trivial.

---

## 7. What We Will Not Test (and why)

- **Spring itself**: we won't test that `@RestController` maps routes —
  the framework does that.
- **Jackson (de)serialization** in isolation — exercised by MockMvc tests.
- **Liquibase internals** — asserted implicitly by integration tests
  booting successfully against a real DB.
- **Bean Validation annotations** exhaustively — one representative test
  per constraint class is enough.

---

## 8. CI Execution

- `mvn test` — unit + WebMvc slices (fast).
- `mvn verify` — adds integration tests (needs Postgres via Testcontainers
  or env-provided URL).
- Fail build on: compile errors, checkstyle (if added), failing tests,
  coverage drop below threshold (if Jacoco enabled in Phase 10).

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Flaky concurrency tests | Mark as `@Tag("concurrency")`, exclude from fast profile, investigate rather than retry. |
| Testcontainers slow on CI | Reuse containers per suite; fall back to env-provided Postgres if needed. |
| Time-based tests drift | Fixed `Clock` injection everywhere (rule, not suggestion). |
| Over-mocking hides wiring bugs | At least one full-stack test per user-facing flow (place, cancel, report). |
