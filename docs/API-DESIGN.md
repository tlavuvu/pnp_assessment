# API Design — Ecommerce Backend

> Phase 1 design artifact. Fixes the public HTTP contract before code is
> written. Every endpoint below is versioned, validated, RBAC-scoped, and
> has a deterministic error envelope.

---

## 1. Conventions

- **Base path**: `/api/v1`
- **Content type**: `application/json; charset=utf-8`
- **Auth**: HTTP Basic (`Authorization: Basic <base64(user:pass)>`)
- **IDs**: numeric `BIGINT` in path params
- **Timestamps**: ISO-8601 UTC (`2026-04-23T10:15:30Z`)
- **Money**: JSON number with 2 decimals (serialized from `BigDecimal`)
- **Pagination**: `?page=0&size=20&sort=name,asc` (Spring conventions)
- **Idempotency**: not implemented in v1; noted as a known risk

### HTTP status code policy
| Code | Use |
|---|---|
| 200 | Successful read |
| 201 | Resource created (has `Location` header) |
| 400 | Malformed JSON / validation error |
| 401 | Missing or bad credentials |
| 403 | Authenticated but role not permitted |
| 404 | Resource not found |
| 409 | Business conflict (insufficient stock, illegal state transition) |
| 429 | Rate limit exhausted |
| 500 | Unhandled server error |

---

## 2. Error Envelope (single shape for all errors)

```json
{
  "timestamp": "2026-04-23T10:15:30Z",
  "status": 409,
  "error": "Conflict",
  "code": "INSUFFICIENT_STOCK",
  "message": "Not enough stock for product 42",
  "path": "/api/v1/orders",
  "fieldErrors": [
    { "field": "items[0].quantity", "message": "must be > 0" }
  ]
}
```

Rules:
- `code` is the **stable machine key**; `message` is human-readable and may
  change. Clients branch on `code`, not `message`.
- `fieldErrors` is present only on validation failures (400).
- No stack traces, no entity fields, no SQL state codes leak out.

Defined `code` values in v1 (as emitted by `GlobalExceptionHandler`
and the security filter chain):

| Code | HTTP | Emitted by |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation on body / params |
| `MALFORMED_REQUEST` | 400 | Unreadable JSON / bad path-var type |
| `INVALID_REPORT_RANGE` | 400 | `from` / `to` cross-field rules |
| `UNAUTHORIZED` | 401 | `AuthenticationEntryPoint` (anonymous / bad creds) |
| `FORBIDDEN` | 403 | `AccessDeniedHandler` (wrong role) |
| `PRODUCT_NOT_FOUND` | 404 | `ProductNotFoundException` |
| `ORDER_NOT_FOUND` | 404 | `OrderNotFoundException` |
| `NOT_FOUND` | 404 | Unmatched routes |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP verb |
| `INSUFFICIENT_STOCK` | 409 | `InsufficientStockException` |
| `ORDER_NOT_CANCELLABLE` | 409 | Cancel on non-`CONFIRMED` order |
| `RATE_LIMITED` | 429 | `RateLimitFilter` (bucket exhausted) |
| `INTERNAL_ERROR` | 500 | Catch-all |

---

## 3. Endpoints

### 3.1 Create product  —  `POST /api/v1/products`

- **Role**: `ADMIN`
- **Rate limited**: yes

Request
```json
{
  "name": "Lavazza Qualità Rossa 1kg",
  "description": "Medium roast blend",
  "price": 189.99,
  "stock": 50
}
```

Validation
- `name`: required, 1..120 chars
- `description`: optional, ≤ 500 chars
- `price`: required, ≥ 0.01, scale 2
- `stock`: required, ≥ 0

Responses
- `201 Created` + `Location: /api/v1/products/{id}` + body (see 3.3)
- `400` validation, `401/403` auth, `429` rate limited

---

### 3.2 List products  —  `GET /api/v1/products`

- **Role**: `USER` or `ADMIN`
- **Query**: `?page=0&size=20&sort=name,asc&search=coffee`
- **200 OK**

```json
{
  "content": [ /* ProductResponse[] */ ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7
}
```

---

### 3.3 Get product  —  `GET /api/v1/products/{id}`

- **Role**: `USER` or `ADMIN`
- **200 OK**

```json
{
  "id": 42,
  "name": "Lavazza Qualità Rossa 1kg",
  "description": "Medium roast blend",
  "price": 189.99,
  "stock": 50,
  "createdAt": "2026-04-23T10:15:30Z",
  "updatedAt": "2026-04-23T10:15:30Z"
}
```

Errors: `404 PRODUCT_NOT_FOUND`

---

### 3.4 Place order  —  `POST /api/v1/orders`

- **Role**: `USER` or `ADMIN`
- **Rate limited**: yes
- **Transactional**: stock check + decrement + order insert in one tx,
  with pessimistic row locks on every product in the order (ordered by id
  ascending to prevent deadlocks).

Request
```json
{
  "customerReference": "cust-7781",
  "items": [
    { "productId": 42, "quantity": 2 },
    { "productId": 77, "quantity": 1 }
  ]
}
```

Validation
- `customerReference`: required, 1..64 chars
- `items`: required, 1..50 entries
- `items[].productId`: required, positive
- `items[].quantity`: required, ≥ 1, ≤ 1000
- Duplicate `productId` across items → `400 VALIDATION_FAILED`

Responses
- `201 Created` + `Location: /api/v1/orders/{id}` + body (see 3.5)
- `400 VALIDATION_FAILED`
- `404 PRODUCT_NOT_FOUND` (any product missing)
- `409 INSUFFICIENT_STOCK` (payload includes which product was short)
- `429 RATE_LIMITED`

Insufficient-stock payload (example):
```json
{
  "timestamp": "2026-04-23T10:15:30Z",
  "status": 409,
  "error": "Conflict",
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for product 42 (requested 2, available 1)",
  "path": "/api/v1/orders"
}
```

---

### 3.5 Get order  —  `GET /api/v1/orders/{id}`

- **Role**: `USER` (own order) or `ADMIN` (any)
- **200 OK**

```json
{
  "id": 1001,
  "customerReference": "cust-7781",
  "status": "CONFIRMED",
  "total": 569.97,
  "items": [
    {
      "productId": 42,
      "productName": "Lavazza Qualità Rossa 1kg",
      "quantity": 2,
      "unitPrice": 189.99,
      "lineTotal": 379.98
    },
    {
      "productId": 77,
      "productName": "Illy Classico 250g",
      "quantity": 1,
      "unitPrice": 189.99,
      "lineTotal": 189.99
    }
  ],
  "createdAt": "2026-04-23T10:15:30Z",
  "updatedAt": "2026-04-23T10:15:30Z"
}
```

Notes
- `unitPrice` is the **price snapshot** taken at order time. Subsequent
  product price changes must not retroactively alter past orders.
- `total = sum(lineTotal)` and is persisted (see `DB-DESIGN.md`) to keep
  reporting cheap.

Errors: `404 ORDER_NOT_FOUND`

---

### 3.6 Cancel order  —  `POST /api/v1/orders/{id}/cancel`

- **Role**: `ADMIN`
- **Rate limited**: yes
- **Transactional**: status change + stock restoration in one tx, with
  pessimistic locks on the referenced products.

Request body: none.

Responses
- `200 OK` with the updated `OrderResponse` body (status = `CANCELLED`).
  Returning the resource — rather than a bare `204` — lets clients
  reconcile UI state without an extra GET.
- `404 ORDER_NOT_FOUND`
- `409 ORDER_NOT_CANCELLABLE` — cancel is allowed only from
  `CONFIRMED`. A second cancel on an already-`CANCELLED` order returns
  409 (not idempotent by design — safer; we can loosen later if
  needed, see ADR-013).
- `429 RATE_LIMITED`

---

### 3.7 Top products report  —  `GET /api/v1/reports/top-products`

- **Role**: `ADMIN`
- **Query**:
  - `from` — ISO-8601 date-time (inclusive), required
  - `to`   — ISO-8601 date-time (exclusive), required, must be > `from`
  - `limit` — integer 1..100, default 10
  - `status` — optional, default `CONFIRMED`; repeatable. Only confirmed
    sales count by default. Cancelled orders are excluded unless the
    caller explicitly asks.

Validation
- `from`/`to` parseable, `to > from`, range ≤ 366 days (avoids accidental
  full-table scans).

Response  `200 OK`
```json
{
  "from": "2026-01-01T00:00:00Z",
  "to":   "2026-04-01T00:00:00Z",
  "limit": 10,
  "results": [
    {
      "productId": 42,
      "productName": "Lavazza Qualità Rossa 1kg",
      "totalQuantity": 312,
      "totalRevenue": 59277.88,
      "orderCount": 180
    }
  ]
}
```

Why ranked by `totalQuantity` (primary) then `totalRevenue` (secondary):
"top-selling" in commerce usually means units moved, not revenue. Revenue
is a deterministic tiebreaker and a useful second signal.

Implementation note: backed by a single native SQL in
`ReportJdbcRepository` — see `ARCHITECTURE.md` §7 for the rationale.

---

## 4. Auth & RBAC Matrix

| Endpoint | Anonymous | USER | ADMIN |
|---|---|---|---|
| `POST /products` | 401 | 403 | ✅ |
| `GET /products` | 401 | ✅ | ✅ |
| `GET /products/{id}` | 401 | ✅ | ✅ |
| `POST /orders` | 401 | ✅ | ✅ |
| `GET /orders/{id}` | 401 | ✅ own | ✅ |
| `POST /orders/{id}/cancel` | 401 | 403 | ✅ |
| `GET /reports/top-products` | 401 | 403 | ✅ |

"USER own" = USER may only read orders whose `customerReference` matches
their principal context (mapping strategy deferred — if not feasible
cleanly, fall back to ADMIN-only read and document the trade-off).

**Implementation status (Phase 9):** "USER own" is **not enforced** in
code. The current rule is "authenticated user can read any order".
Enforcing ownership would require either storing the principal on
`orders.customer_reference` on placement and a method-level
`@PreAuthorize` check, or an owner column. Deferred to follow-up;
flagged as underengineered in `docs/SOLUTION.md`.

---

## 5. Rate Limiting

- **Buckets**: per authenticated principal (fallback IP).
- **Defaults**: write endpoints 30 req/min; reports 10 req/min; reads
  unthrottled at the app layer.
- **Response on exhaustion**: `429` with error envelope
  (`code: RATE_LIMITED`) and `Retry-After` header in seconds.

Optional enhancement (declared, not required by brief).

---

## 6. Versioning & Evolution

- Breaking changes → new path prefix (`/api/v2`). Non-breaking additions
  (new optional fields) ship under `/api/v1` and must be tolerant to
  unknown fields on input.
- DTOs are independent of entities — entity renames never break the API.

---

## 7. Examples (curl)

```bash
# Place an order
curl -u user:userpw -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "customerReference": "cust-7781",
    "items": [{"productId":42,"quantity":2}]
  }'

# Cancel
curl -u admin:adminpw -X POST http://localhost:8080/api/v1/orders/1001/cancel

# Top products for Q1
curl -u admin:adminpw \
  "http://localhost:8080/api/v1/reports/top-products?from=2026-01-01T00:00:00Z&to=2026-04-01T00:00:00Z&limit=10"
```

---

## 8. Out of Scope (v1)

- Update / delete product (price changes handled via create-new pattern —
  or added in Phase 6 if time permits; documented as deferred).
- Partial cancellation / returns.
- Webhooks / async notifications.
- Multi-currency, tax, discounts, promotions.
