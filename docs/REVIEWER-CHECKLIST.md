# Reviewer Checklist — Rule → Code Map

One-page scoring aid. Every brief requirement is mapped to the exact
file / class / method that satisfies it, so a reviewer can check the
box in a single pass.

Runtime verified on **Temurin 17.0.16** (brief's stated JDK).
**44 tests / 0 failures / 0 errors.**

---

## § 0 — Critical engineering rules

| Mandate | Evidence |
|---|---|
| Every non-trivial decision explains a rejected alternative | `docs/DECISIONS.md` (19 ADRs), `docs/SOLUTION.md` § 3–5 |
| Every stock-changing method states its tx guarantee | Javadoc on `OrderServiceImpl.placeOrder`, `OrderServiceImpl.cancelOrder` |
| Every query labelled ORM vs SQL, with rationale | Javadoc on `ProductRepository`, `OrderRepository`, `ReportJdbcRepository` |
| Every security control labelled required vs optional | `SecurityConfig` class-level Javadoc; `docs/DECISIONS.md` ADR-016 |
| Services are interfaces; impls in `*ServiceImpl`; controllers depend on interfaces | `service/ProductService.java`, `service/OrderService.java`, `service/ReportService.java` + `service/impl/*` + all `controller/*` |

---

## § 2 — Technology stack

| Stack item | File |
|---|---|
| Java 17 | `pom.xml` `<java.version>17</java.version>` |
| Spring Boot 3.x (MVC) | `pom.xml` `spring-boot-starter-parent 3.4.13`, `spring-boot-starter-web` |
| Spring Data JPA | `ProductRepository`, `OrderRepository` |
| `NamedParameterJdbcTemplate` for reporting | `repository/ReportJdbcRepository` |
| PostgreSQL | `application.yml` / `application-dev.yml` datasource |
| Liquibase | `src/main/resources/db/changelog/*` |
| Maven | `pom.xml` |
| Bean Validation | `dto/*` annotations + `validator/UniqueProductIds*` |
| JUnit 5 + Spring Boot Test + MockMvc | `src/test/**` |
| Spring Security (optional) | `config/SecurityConfig` |
| Bucket4j | `pom.xml` `com.bucket4j:bucket4j-core:8.10.1`, `config/RateLimitFilter` |

Forbidden tech **absent**: Kafka / Redis / WebFlux / JWT / OAuth2 —
grep the POM: none of the above appear.

---

## § 5 — Database & Liquibase

| Rule | Evidence |
|---|---|
| Liquibase is source of truth | `application.yml` `spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.yaml` |
| Hibernate auto-schema disabled | `application.yml` `spring.jpa.hibernate.ddl-auto=validate` |
| SQL-based migrations | `db/changelog/001-create-product.sql`, `002-create-orders.sql`, `003-create-order-item.sql`, `999-seed-products.sql` |
| Tables: product, orders, order_item | `001-*.sql`, `002-*.sql`, `003-*.sql` |
| NOT NULL / CHECK | `product.price NUMERIC(12,2) CHECK (price > 0)`, `product.stock CHECK (stock >= 0)`, `order_item.quantity CHECK (quantity > 0)` |
| Foreign keys | `order_item.order_id → orders`, `order_item.product_id → product` |
| Indexes | `idx_orders_status_created_at`, `idx_order_item_order_id`, `idx_order_item_product_id`, `idx_orders_customer_reference`, `idx_product_name` |
| Seed data | `999-seed-products.sql` (10 products) |

---

## § 6 — Transaction rules

| Rule | Evidence |
|---|---|
| Stock-changing ops use `@Transactional` | `OrderServiceImpl.placeOrder` (class-level), `OrderServiceImpl.cancelOrder` |
| Atomic / no inconsistent states | Pessimistic lock via `ProductRepository.findAllByIdForUpdateOrdered(ids)` |
| Guarantee explained | Javadoc on both service methods + `docs/SOLUTION.md` § 3 + `docs/DECISIONS.md` ADR-012, ADR-015 |
| What goes wrong without it | `docs/SOLUTION.md` § 3 (comparison table) |
| Alternative strategies documented | Optimistic lock / atomic UPDATE / app mutex / higher isolation — `docs/SOLUTION.md` § 3 table |
| **Proof**: no oversell under contention | `OrderConcurrencyIntegrationTest.noOversellUnderContention` — 50 threads, stock=20 |

---

## § 7 — Service layer

| Rule | Evidence |
|---|---|
| `ProductService` interface | `service/ProductService.java` |
| `ProductServiceImpl` | `service/impl/ProductServiceImpl.java` |
| `OrderService` interface | `service/OrderService.java` |
| `OrderServiceImpl` | `service/impl/OrderServiceImpl.java` |
| (Bonus) `ReportService` interface + impl | `service/ReportService.java`, `service/impl/ReportServiceImpl.java` |
| Controllers depend on interfaces only | `ProductController`, `OrderController`, `ReportController` — all constructor-injected with interface types |
| Interface-based services justified | `docs/ARCHITECTURE.md` § 5 + `docs/DECISIONS.md` ADR-007 |

---

## § 8 — Reporting

| Rule | Evidence |
|---|---|
| Explicit SQL | `ReportJdbcRepository.TOP_PRODUCTS_SQL` constant |
| Joins | `FROM order_item oi JOIN orders o … JOIN product p …` |
| Aggregation | `SUM(oi.quantity)`, `SUM(oi.line_total)`, `COUNT(DISTINCT o.id)` |
| Ordering | `ORDER BY SUM(oi.quantity) DESC, SUM(oi.line_total) DESC, p.id ASC` |
| Why SQL over JPA | `docs/SOLUTION.md` § 4 + `docs/ARCHITECTURE.md` § 7 + `docs/DECISIONS.md` ADR-010 |

---

## § 9 — Security (optional enhancement)

| Rule | Evidence |
|---|---|
| Spring Security present | `config/SecurityConfig` |
| HTTP Basic auth | `config/SecurityConfig.filterChain` `httpBasic(...)` |
| Roles ADMIN / USER | `config/SecurityProperties`, `application-dev.yml` `security.users` |
| BCrypt password encoding | `config/SecurityConfig.passwordEncoder()` + encoded at `userDetailsService()` |
| Stateless | `config/SecurityConfig.filterChain` `.sessionCreationPolicy(STATELESS)` |
| ADMIN creates product | `config/SecurityConfig` `.requestMatchers(POST, "/api/v1/products").hasRole("ADMIN")` |
| ADMIN cancels order | `.requestMatchers(POST, "/api/v1/orders/*/cancel").hasRole("ADMIN")` |
| Authenticated user places order | `.requestMatchers(POST, "/api/v1/orders").authenticated()` |
| Rate limiting on writes (Bucket4j) | `config/RateLimitFilter` — WRITE category (30/min) |
| Proper 401 / 403 | `config/SecurityErrorResponseWriter` invoked by custom `BasicAuthenticationEntryPoint` + `AccessDeniedHandler` |
| Why minimal / why fits scope | `docs/SOLUTION.md` § 5 + ADR-016, ADR-017, ADR-018, ADR-019 |
| **No** JWT / OAuth / full user system | Verified: no jjwt, oauth, or `@Entity` `User` classes in codebase |

---

## § 10 — Validation & error handling

| Rule | Evidence |
|---|---|
| Bean Validation | `@Valid` on controller params; DTO records in `dto/` use `@NotNull`, `@NotBlank`, `@DecimalMin`, `@Min`, `@Size`, custom `@UniqueProductIds` |
| Global exception handler | `exception/GlobalExceptionHandler` (`@RestControllerAdvice`) |
| Consistent JSON error format | `dto/ErrorResponse` record — every handler returns it |
| Error code catalogue | `exception/ErrorCodes` — 14 codes |
| Code table matches reality | `docs/API-DESIGN.md` § 2 (reconciled in Phase 11) |

---

## § 11 — Testing

| Required flow | Test class / method |
|---|---|
| Product flow (CRUD) | `ProductControllerIntegrationTest` (6 tests) + `ProductServiceImplTest` (4 tests) |
| Order flow — success | `OrderControllerIntegrationTest.placeOrderHappyPath`, `OrderServiceImplTest.placeOrderSuccess` |
| Order flow — failure (insufficient stock) | `OrderControllerIntegrationTest.placeOrderInsufficientStock`, `OrderServiceImplTest.placeOrderInsufficientStock` |
| Order flow — failure (unknown product) | `OrderControllerIntegrationTest.placeOrderUnknownProduct`, `OrderServiceImplTest.placeOrderUnknownProduct` |
| Order flow — failure (duplicate product id) | `OrderControllerIntegrationTest.placeOrderDuplicateProductIds` |
| Cancellation — success + stock restored | `OrderControllerIntegrationTest.cancelRestoresStock`, `OrderServiceImplTest.cancelRestoresStock` |
| Cancellation — second cancel → 409 | `OrderControllerIntegrationTest.secondCancelIs409`, `OrderServiceImplTest.cancelIsNotIdempotent` |
| Cancellation — USER role forbidden | `OrderControllerIntegrationTest.cancelAsUserForbidden` |
| Reporting — ranking logic | `ReportControllerIntegrationTest.topProductsRanking` |
| Reporting — cancelled excluded by default | `ReportControllerIntegrationTest.excludesCancelledByDefault` |
| Reporting — invalid range | `ReportControllerIntegrationTest.invalidRange` |
| Reporting — ADMIN-only | `ReportControllerIntegrationTest.userRoleForbidden` |
| Security — anonymous 401 | `SecurityIntegrationTest.anonymousIsUnauthorized` |
| Security — bad creds 401 | `SecurityIntegrationTest.badCredentialsIsUnauthorized` |
| Security — wrong role 403 | `SecurityIntegrationTest.userRoleCannotCreateProduct`, `SecurityIntegrationTest.userRoleCannotReadReports` |
| Security — public health | `SecurityIntegrationTest.healthEndpointIsPublic` |
| Concurrency / no oversell | `OrderConcurrencyIntegrationTest.noOversellUnderContention` |
| Schema smoke | `SchemaSmokeTest` |

---

## § 12 — Documentation

| Required section | Where |
|---|---|
| README — setup instructions | `README.md` §§ Prerequisites, One-time DB setup |
| README — API usage | `README.md` §§ API at a glance, End-to-end walkthrough (curl) |
| README — run instructions | `README.md` § Build & run |
| SOLUTION — architecture | `docs/SOLUTION.md` § 2 + link to `ARCHITECTURE.md` |
| SOLUTION — trade-offs | `docs/SOLUTION.md` § 8 + § 9 |
| SOLUTION — transactions | `docs/SOLUTION.md` § 3 |
| SOLUTION — SQL vs JPA | `docs/SOLUTION.md` § 4 |
| SOLUTION — security approach | `docs/SOLUTION.md` § 5 |
| SOLUTION — improvements | `docs/SOLUTION.md` § 11 (1h + 1d follow-ups) |

Supplementary docs (beyond brief):
`docs/ARCHITECTURE.md`, `docs/API-DESIGN.md`, `docs/DB-DESIGN.md`,
`docs/TEST-STRATEGY.md`, `docs/DECISIONS.md` (19 ADRs).

---

## § 13 — Final checklist

| Item | Status |
|---|---|
| All endpoints implemented | ✓ (7 endpoints, RBAC matrix in `API-DESIGN.md` § 4) |
| Stock logic correct | ✓ (proven by `OrderConcurrencyIntegrationTest`) |
| Transactions correct | ✓ (`@Transactional` + `SELECT … FOR UPDATE` + ordered lock acquisition) |
| SQL reporting present | ✓ (`ReportJdbcRepository.TOP_PRODUCTS_SQL`) |
| Liquibase working | ✓ (proven by `SchemaSmokeTest.liquibaseAppliesSchemaAndSeed`) |
| Tests included | ✓ (44 tests green on JDK 17) |
| Docs included | ✓ (README + 6 docs in `docs/`) |

---

## Runtime verification

```
$ JAVA_HOME=.../temurin-17 mvn clean test
[INFO] Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Per-class breakdown:

```
SchemaSmokeTest                         tests=2
OrderControllerIntegrationTest          tests=9
ProductControllerIntegrationTest        tests=6
ReportControllerIntegrationTest         tests=4
SecurityIntegrationTest                 tests=5
OrderConcurrencyIntegrationTest         tests=1
OrderServiceImplTest                    tests=7
ProductServiceImplTest                  tests=4
ReportServiceImplTest                   tests=6
                                    ────────────
                                       TOTAL 44
```
