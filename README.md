# PnP Ecommerce Assessment

Backend service for the Mid-Level Java Developer: Ecommerce Systems
take-home. Spring Boot 3 / Java 17 / PostgreSQL / Liquibase.

> This README is the **quick-start**. Design lives in [`docs/`](./docs):
> [SOLUTION](./docs/SOLUTION.md) (start here),
> [ARCHITECTURE](./docs/ARCHITECTURE.md),
> [API-DESIGN](./docs/API-DESIGN.md),
> [DB-DESIGN](./docs/DB-DESIGN.md),
> [TEST-STRATEGY](./docs/TEST-STRATEGY.md),
> [DECISIONS](./docs/DECISIONS.md).

---

## Capabilities

- Product catalogue (create / list / get)
- Order placement with stock validation (transactional, no oversell)
- Order cancellation with stock restoration
- Reporting — top-selling products by date range (native SQL)
- Bean Validation + global error envelope
- HTTP Basic auth + RBAC (ADMIN / USER)
- Rate limiting on write endpoints (Bucket4j, in-memory)

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 17 |
| Framework | Spring Boot 3.4.13 (Spring MVC) |
| Persistence (CRUD) | Spring Data JPA |
| Persistence (reporting) | `NamedParameterJdbcTemplate` |
| Database | PostgreSQL 16 |
| Migrations | Liquibase (SQL-format changelogs) |
| Build | Maven (via `spring-boot-starter-parent`) |
| Validation | Jakarta Bean Validation |
| Security | Spring Security (HTTP Basic, stateless) |
| Rate limit | Bucket4j 8.10.1 |
| Tests | JUnit 5 + MockMvc + Testcontainers Postgres |

Excluded by design: Kafka, Redis, microservices, CQRS, WebFlux, JWT/OAuth2.
See [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md#1-goals--non-goals).

---

## Prerequisites

- JDK 17 (`java -version` → 17.x)
- Maven 3.9+ (`mvn -v`)
- Docker Desktop / OrbStack — **required** for integration tests
  (Testcontainers spins up Postgres)
- PostgreSQL 16 — required for `mvn spring-boot:run` in the `dev` profile

---

## One-time local database setup

```bash
# Start a Postgres 16 container for local dev (matches prod dialect).
docker run -d --name pnp-postgres \
  -e POSTGRES_DB=pnp_ecommerce \
  -e POSTGRES_USER=pnp \
  -e POSTGRES_PASSWORD=pnp \
  -p 5432:5432 \
  postgres:16-alpine
```

Defaults in `application.yml` point at `localhost:5432/pnp_ecommerce` with
user/password `pnp`/`pnp`. Override via env vars when needed:

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/pnp_ecommerce` |
| `DB_USER` | `pnp` |
| `DB_PASSWORD` | `pnp` |
| `SERVER_PORT` | `8080` |
| `SPRING_PROFILES_ACTIVE` | `dev` |

---

## Build & run

```bash
# Build (runs unit tests)
mvn clean verify

# Boot locally against your Postgres (dev profile)
mvn spring-boot:run

# Package a runnable jar
mvn clean package
java -jar target/ecommerce.jar
```

Health check: `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`.

---

## Tests

```bash
# Run the full test suite (unit + Testcontainers integration tests).
# Docker must be running — Testcontainers starts a Postgres 16 container.
mvn test

# Same suite, plus full verify lifecycle (package etc.).
mvn verify
```

Current suite: **44 tests, 0 failures**. The concurrency proof
(`OrderConcurrencyIntegrationTest`) is the highest-signal spec — it
fires 50 threads at a product with stock = 20 and asserts no oversell
plus deterministic outcomes. See
[`docs/TEST-STRATEGY.md`](./docs/TEST-STRATEGY.md).

---

## Project layout

```
src/main/java/com/pnp/ecommerce/
├── EcommerceApplication.java
├── config/        # SecurityConfig, RateLimitFilter, Clock bean
├── controller/    # REST endpoints (no business logic)
├── dto/           # Request/Response records
├── entity/        # JPA entities
├── enumtype/      # Domain enums (OrderStatus, …)
├── exception/     # Domain exceptions + GlobalExceptionHandler
├── mapper/        # Entity ↔ DTO mappers
├── repository/    # Spring Data + JdbcTemplate repositories
├── service/       # Service *interfaces* (what controllers depend on)
│   └── impl/      # *ServiceImpl — business logic + @Transactional
└── util/          # Clock provider, helpers

src/main/resources/
├── application.yml
├── application-dev.yml
└── db/changelog/  # Liquibase SQL changelogs + seed data
```

---

## API at a glance

| Method | Path | Role |
|---|---|---|
| POST | `/api/v1/products` | ADMIN |
| GET  | `/api/v1/products` | USER, ADMIN |
| GET  | `/api/v1/products/{id}` | USER, ADMIN |
| POST | `/api/v1/orders` | USER, ADMIN |
| GET  | `/api/v1/orders/{id}` | USER (own) / ADMIN |
| POST | `/api/v1/orders/{id}/cancel` | ADMIN |
| GET  | `/api/v1/reports/top-products` | ADMIN |

Full contract with error envelope in
[`docs/API-DESIGN.md`](./docs/API-DESIGN.md).

Seeded credentials (dev / test profiles only — see
[`application-dev.yml`](./src/main/resources/application-dev.yml)):

| User | Password | Roles |
|---|---|---|
| `admin` | `admin123` | `ADMIN` |
| `user`  | `user123`  | `USER` |

---

## End-to-end walkthrough (curl)

Boot the app (`mvn spring-boot:run`), then from another shell:

```bash
# 0. health (public)
curl -s http://localhost:8080/actuator/health

# 1. list catalogue (any authenticated user)
curl -s -u user:user123 \
  http://localhost:8080/api/v1/products | jq .

# 2. admin creates a new product
curl -s -u admin:admin123 \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "Lavazza Oro 1kg",
        "description": "Medium roast, 100% arabica",
        "price": 249.99,
        "stock": 40
      }' \
  http://localhost:8080/api/v1/products -i

# 3. place an order (any authenticated user)
curl -s -u user:user123 \
  -H 'Content-Type: application/json' \
  -d '{
        "customerReference": "cust-0001",
        "items": [
          { "productId": 1, "quantity": 2 },
          { "productId": 2, "quantity": 1 }
        ]
      }' \
  http://localhost:8080/api/v1/orders -i

# 4. read the order back — snapshot pricing persisted on items.
#    Replace {id} with the numeric id returned in step 3's Location header.
curl -s -u user:user123 \
  http://localhost:8080/api/v1/orders/1 | jq .

# 5. try to oversell — expect 409 INSUFFICIENT_STOCK
curl -s -u user:user123 \
  -H 'Content-Type: application/json' \
  -d '{
        "customerReference": "cust-0001",
        "items": [ { "productId": 1, "quantity": 999999 } ]
      }' \
  http://localhost:8080/api/v1/orders -i

# 6. admin cancels the order — stock is restored in the same tx
curl -s -u admin:admin123 -X POST \
  http://localhost:8080/api/v1/orders/1/cancel -i

# 7. top-products report for Q1 (ADMIN only)
curl -s -u admin:admin123 \
  "http://localhost:8080/api/v1/reports/top-products?\
from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:00Z&limit=5" | jq .

# 8. role enforcement: USER hitting an ADMIN endpoint returns 403
curl -s -u user:user123 -X POST \
  http://localhost:8080/api/v1/orders/1/cancel -i
```

Every response (success and error) carries the envelope shape
described in [`docs/API-DESIGN.md`](./docs/API-DESIGN.md) §2.

---

## Status

- [x] Phase 1 — Architecture & design (docs)
- [x] Phase 2 — Project scaffolding
- [x] Phase 3 — Liquibase schema
- [x] Phase 4 — Entities + repositories
- [x] Phase 5 — DTOs + validation
- [x] Phase 6 — Services (interfaces + impl)
- [x] Phase 7 — Controllers + error handling
- [x] Phase 8 — Reporting (SQL)
- [x] Phase 9 — Security (optional enhancement)
- [x] Phase 10 — Tests
- [x] Phase 11 — Documentation
- [ ] Phase 12 — Final review
