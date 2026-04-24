# Database Design — Ecommerce Backend

> Phase 1 design artifact. Liquibase is the source of truth; Hibernate
> auto-DDL is disabled. Column types, constraints, and indexes below are
> the contract Phase 3 will encode as SQL changelogs.

---

## 1. ERD (logical)

```
┌───────────────┐          ┌───────────────┐          ┌───────────────┐
│   product     │          │     orders    │          │  order_item   │
├───────────────┤          ├───────────────┤          ├───────────────┤
│ id (PK)       │◄── FK ───│ id (PK)       │──┐       │ id (PK)       │
│ name          │          │ customer_ref  │  │  FK   │ order_id (FK) │─┐
│ description   │          │ status        │  └──────►│ product_id(FK)│ │
│ price         │          │ total         │          │ quantity      │ │
│ stock         │          │ created_at    │          │ unit_price    │ │
│ created_at    │          │ updated_at    │          │ line_total    │ │
│ updated_at    │          └───────────────┘          └───────────────┘ │
└───────────────┘                                                       │
        ▲                                                               │
        └───────────────────── FK (product_id) ─────────────────────────┘
```

One `orders` row → many `order_item` rows (strong composition).
`order_item.product_id` → `product.id` (reference, not composition).
`order_item.order_id` has `ON DELETE CASCADE` only for test/dev cleanup;
in production we never physically delete orders (we cancel them).

---

## 2. Tables

### 2.1 `product`

| Column        | Type          | Null | Default            | Notes |
|---------------|---------------|------|--------------------|-------|
| `id`          | BIGSERIAL     | NO   | identity           | PK |
| `name`        | VARCHAR(120)  | NO   |                    | CHECK `length(name) >= 1` |
| `description` | VARCHAR(500)  | YES  |                    | |
| `price`       | NUMERIC(12,2) | NO   |                    | CHECK `price >= 0` |
| `stock`       | INTEGER       | NO   | 0                  | CHECK `stock >= 0` |
| `created_at`  | TIMESTAMPTZ   | NO   | `now()`            | |
| `updated_at`  | TIMESTAMPTZ   | NO   | `now()`            | Updated by trigger or JPA `@PreUpdate` |

Indexes:
- `PRIMARY KEY (id)`
- `UNIQUE (lower(name))` — avoid duplicate catalogue entries with only
  case differences. (Optional; ship if Phase 3 time permits.)
- `INDEX ix_product_name (name)` for search/sort.

### 2.2 `orders`

Named `orders` (not `order`) because `ORDER` is an SQL reserved word.

| Column               | Type           | Null | Default     | Notes |
|----------------------|----------------|------|-------------|-------|
| `id`                 | BIGSERIAL      | NO   | identity    | PK |
| `customer_reference` | VARCHAR(64)    | NO   |             | CHECK `length(customer_reference) >= 1` |
| `status`             | VARCHAR(16)    | NO   | `'CONFIRMED'` | CHECK IN (`'PENDING'`,`'CONFIRMED'`,`'CANCELLED'`) |
| `total`              | NUMERIC(12,2)  | NO   | 0           | CHECK `total >= 0`. Derived, stored for reporting. |
| `created_at`         | TIMESTAMPTZ    | NO   | `now()`     | Used by reports |
| `updated_at`         | TIMESTAMPTZ    | NO   | `now()`     | |

Indexes:
- `PRIMARY KEY (id)`
- `INDEX ix_orders_status_created_at (status, created_at)` — supports the
  top-products report (`WHERE status='CONFIRMED' AND created_at BETWEEN …`)
- `INDEX ix_orders_customer_reference (customer_reference)` — for
  per-customer lookups (future-proofing; cheap).

### 2.3 `order_item`

| Column        | Type           | Null | Default  | Notes |
|---------------|----------------|------|----------|-------|
| `id`          | BIGSERIAL      | NO   | identity | PK |
| `order_id`    | BIGINT         | NO   |          | FK → `orders(id)` ON DELETE CASCADE |
| `product_id`  | BIGINT         | NO   |          | FK → `product(id)` ON DELETE RESTRICT |
| `quantity`    | INTEGER        | NO   |          | CHECK `quantity > 0` |
| `unit_price`  | NUMERIC(12,2)  | NO   |          | CHECK `unit_price >= 0`. Snapshot at order time. |
| `line_total`  | NUMERIC(12,2)  | NO   |          | CHECK `line_total = quantity * unit_price` (DB enforces arithmetic correctness). |

Indexes:
- `PRIMARY KEY (id)`
- `INDEX ix_order_item_order_id (order_id)` — fetch items of an order
- `INDEX ix_order_item_product_id (product_id)` — powers the top-products
  aggregation join

Why snapshot `unit_price` and `line_total`:
- Historical correctness: price changes on `product` must never alter
  past orders or reports.
- Report speed: no runtime multiplication or price lookup needed.
- Integrity: the CHECK constraint stops bugs that compute `line_total`
  wrong in app code.

---

## 3. Referential Integrity

| FK | Action | Why |
|---|---|---|
| `order_item.order_id → orders.id` | `ON DELETE CASCADE` | If an order is physically deleted (dev/test), its lines go with it. |
| `order_item.product_id → product.id` | `ON DELETE RESTRICT` | Deleting a product that has been ordered is forbidden — the order history must remain consistent. |

Products should be **soft-deleted** (flag) in future; out of scope for v1.

---

## 4. Concurrency Model

- Stock mutations happen only inside a transaction that has already taken
  a **row-level pessimistic lock** on the affected `product` rows, in
  ascending `id` order, via JPA `LockModeType.PESSIMISTIC_WRITE` (maps to
  Postgres `SELECT … FOR UPDATE`).
- Deadlock avoidance: **always lock by `product.id` ascending**. Two
  concurrent orders on `{42, 77}` and `{77, 42}` both lock `42` first,
  then `77`. No circular wait.
- `CHECK (stock >= 0)` on `product` is a last-line-of-defense: even if app
  logic is wrong, the DB refuses to go negative.

See `ARCHITECTURE.md` §6 for rejected alternatives (optimistic locking,
conditional UPDATE).

---

## 5. Liquibase Plan

File layout (to be implemented in Phase 3):

```
src/main/resources/db/changelog/
├── db.changelog-master.xml
└── changes/
    ├── 001-create-product.sql
    ├── 002-create-orders.sql
    ├── 003-create-order-item.sql
    ├── 004-indexes.sql
    ├── 005-seed-products.sql
    └── 006-seed-users.sql         (if security ships in Phase 9)
```

Rules:
- **SQL-formatted** changelogs, not XML DSL. Closer to real DB output,
  easier for reviewers to read.
- Every changeset has an explicit `--changeset author:id` header, a
  `--rollback` block, and is **immutable once merged**. Corrections ship
  as new changesets.
- Seed data lives in separate changesets so prod can skip them via
  Liquibase `context` (e.g. `context: dev,test`).

---

## 6. Seed Data

### Products (≥ 10 rows)
Representative spread of prices and stock levels so reports and pagination
aren't trivial:

| name | price | stock |
|---|---|---|
| Lavazza Qualità Rossa 1kg | 189.99 | 50 |
| Illy Classico 250g        |  99.50 | 30 |
| Jacobs Krönung 500g       | 129.00 | 20 |
| Nespresso Original Pods x10 |  79.99 | 100 |
| Moka Pot 6-cup            | 449.00 |  8 |
| Milk Frother              | 299.00 |  5 |
| Coffee Grinder Manual     | 349.00 | 12 |
| V60 Dripper               | 159.00 | 40 |
| Filter Papers x100        |  49.99 | 200 |
| Kettle Gooseneck          | 899.00 |  3 |

### Users (if Phase 9 ships)
- `admin / admin123` → role `ADMIN`
- `user  / user123`  → role `USER`

BCrypt hashes are generated at build time; plaintext values are dev-only
and documented as such in `README.md`.

### Orders
No seeded orders. Order fixtures are created by integration tests so they
don't contaminate manual QA of report ranges.

---

## 7. Reporting SQL (canonical shape)

Target query for `GET /reports/top-products`:

```sql
SELECT  p.id                        AS product_id,
        p.name                      AS product_name,
        SUM(oi.quantity)            AS total_quantity,
        SUM(oi.line_total)          AS total_revenue,
        COUNT(DISTINCT o.id)        AS order_count
FROM    order_item oi
JOIN    orders  o ON o.id = oi.order_id
JOIN    product p ON p.id = oi.product_id
WHERE   o.status = 'CONFIRMED'
  AND   o.created_at >= :from
  AND   o.created_at <  :to
GROUP BY p.id, p.name
ORDER BY total_quantity DESC, total_revenue DESC
LIMIT   :limit;
```

Why this shape:
- `status = 'CONFIRMED'` excludes cancelled orders from "top sellers".
- Half-open range `[from, to)` avoids double-counting on boundary seconds.
- `GROUP BY p.id, p.name` is safe because name is functionally dependent
  on id (fine under Postgres strict grouping).
- `ORDER BY total_quantity DESC, total_revenue DESC` — units first,
  revenue as deterministic tiebreaker.
- Uses only columns covered by `ix_orders_status_created_at` and
  `ix_order_item_product_id` for efficient plans.

**ORM vs SQL classification**: this is **SQL-driven** via
`NamedParameterJdbcTemplate`. JPQL would express it, but native SQL is:
(a) explicitly requested by the brief, (b) more idiomatic for aggregation,
(c) future-proof toward window functions / CTEs.

---

## 8. Data Types Policy

- Money → `NUMERIC(12,2)` ↔ `BigDecimal`. Never `double`.
- Integers → `INTEGER` unless we need > 2^31 (we don't, for stock/qty).
- PKs → `BIGINT` / `Long`, identity generation.
- Timestamps → `TIMESTAMPTZ` ↔ `Instant`. Always stored in UTC.
- Enums → `VARCHAR(16)` with CHECK constraint; mapped via JPA
  `@Enumerated(EnumType.STRING)`. Reason: Postgres enum types are a
  migration tax for zero runtime benefit at this scale.

---

## 9. Migration Reversibility

- Every Liquibase changeset provides a `--rollback` block (DROP TABLE,
  DROP INDEX, etc.). Reviewers can grade migration hygiene without
  running the app.
- Seed changesets are reversible via `DELETE WHERE` on known sentinel
  values.

---

## 10. Risks & Future Work

| Risk | Mitigation path |
|---|---|
| No soft delete on product — cannot retire SKUs without breaking FK | Add `active` BOOLEAN in a later migration |
| No partitioning on `orders` — reports scan full history | Acceptable for brief; partition by `created_at` month later if needed |
| No currency column on `product` — single-currency assumption | Documented; add `currency CHAR(3)` in v2 |
| Derived `total` on `orders` can drift from `SUM(line_total)` | Enforce in service layer + add a DB trigger or a nightly reconciliation job (deferred) |
| `order_item.line_total` CHECK uses stored arithmetic — tiny rounding risk on future currency math | Acceptable at NUMERIC(12,2) precision |
