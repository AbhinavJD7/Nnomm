# Nnomm Flash-Deal High-Concurrency Engine

High-throughput, distributed flash-deal claiming system built in **Kotlin** and **Ktor**, backed by **PostgreSQL 15**, **HikariCP**, **Flyway**, and **Testcontainers**.

The system is designed and verified to run in a distributed multi-instance environment (`app-instance-1` on port `8080`, `app-instance-2` on port `8081`) sharing a single PostgreSQL 15 database, providing zero overselling guarantees, distributed idempotency, constant-time HMAC payment verification, and race-condition safety.

---

## 🚀 Quick Start

### 1. Prerequisites
- **Docker Desktop** (Engine 20.10+ / Docker Compose v2)
- **Java 21 JDK** (for local Gradle builds; not needed if using Docker)

### 2. Run Distributed Cluster with Docker Compose
Starts PostgreSQL (port `5432`), `app-instance-1` (port `8080`), and `app-instance-2` (port `8081`):
```bash
docker compose up --build
```
Both instances automatically apply Flyway database migrations and start the background `ClaimExpiryWorker`.

### 3. Run Automated Concurrency & Integration Tests
Executes all **34 automated tests** against a dynamic PostgreSQL 15 container:
```bash
./gradlew test --rerun-tasks
```

---

## 🏗 System Architecture

```
                       ┌───────────────────────┐
                       │   Client / Webhooks   │
                       └───────────┬───────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
          HTTP :8080│                             │HTTP :8081
                    ▼                             ▼
       ┌─────────────────────────┐   ┌─────────────────────────┐
       │     app-instance-1      │   │     app-instance-2      │
       │  (Ktor Netty / Java 21) │   │  (Ktor Netty / Java 21) │
       │   ClaimExpiryWorker     │   │   ClaimExpiryWorker     │
       └────────────┬────────────┘   └────────────┬────────────┘
                    │                             │
                    │      HikariCP Pool (25)     │
                    └──────────────┬──────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────┐
                    │    PostgreSQL 15 DB     │
                    │  (Row-Level Locks / MVCC│
                    │   Partial Unique Index) │
                    └─────────────────────────┘
```

---

## 🔒 Core Invariants & Concurrency Architecture

### 1. Zero Overselling Invariant
- **Mechanism**: Atomic conditional SQL decrement at the database level:
  ```sql
  UPDATE deals 
  SET stock_available = stock_available - ? 
  WHERE id = ? AND stock_available >= ?;
  ```
- **Concurrency Test Verification**: 200 concurrent coroutines fighting for 50 items across multiple connections: exactly 50 succeed (201 Created), 150 receive `409 SOLD_OUT`, and `stock_available` reaches exactly 0.

### 2. Max 1 Active Claim Per Customer Per Deal
- **Mechanism**: Enforced declaratively by PostgreSQL partial unique index:
  ```sql
  CREATE UNIQUE INDEX uq_one_active_claim_per_customer_deal 
  ON claims (deal_id, customer_id) 
  WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');
  ```
- Any concurrent claim attempt for the same deal by the same customer fails immediately with `409 ALREADY_CLAIMED`.

### 3. Distributed Idempotency Pipeline
- **Scope**: 24 hours per customer and key (`(customer_id, idempotency_key)`).
- **Concurrency Protection**:
  1. Atomic insert using `INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING;`
  2. Row-level lock: `SELECT ... FOR UPDATE` serializes concurrent identical requests.
  3. In-flight requests return `409 REQUEST_IN_PROGRESS`.
  4. Crashed requests (>60s in `IN_PROGRESS`) are automatically taken over.
  5. Completed requests replay the exact original status code and response body with header:
     ```http
     Idempotent-Replayed: true
     ```
  6. Attempting to reuse an idempotency key with different deal or quantity returns `422 IDEMPOTENCY_KEY_REUSED`.

### 4. Expiry vs. Payment Race Condition
- **Mechanism**: `ClaimExpiryWorker` scans overdue reservations using:
  ```sql
  SELECT id, deal_id, quantity 
  FROM claims 
  WHERE status = 'RESERVED' AND expires_at <= NOW() 
  LIMIT 100 
  FOR UPDATE SKIP LOCKED;
  ```
  `SKIP LOCKED` allows multiple app instances to reap expired claims in parallel without lock contention or deadlocks.
- **Race Resolution**:
  - If payment locks the claim first: Claim transitions to `PAID`, stock remains deducted.
  - If expiry locks the claim first: Claim transitions to `EXPIRED`, stock is restored (`+quantity`), and late payment transitions to `REFUND_PENDING` without re-deducting stock.

### 5. Webhook Security & Anti-Enumeration
- **HMAC-SHA256**: Signatures verified against secret using constant-time comparison (`MessageDigest.isEqual`) to prevent timing attacks.
- **Clock Drift**: Requests with timestamp drift `> 300` seconds are rejected with `401 UNAUTHENTICATED`.
- **Deduplication**: Incoming events recorded in `processed_webhooks` table; duplicate `event_id` returns idempotent `200 OK`.
- **Anti-Enumeration Guard**: `GET /v1/claims/{claimId}` returns `404 NOT_FOUND` (never 403) when accessed by unauthorized users to prevent UUID enumeration attacks.

---

## 📡 API Reference

### 1. Create Deal
`POST /v1/deals` (Requires `restaurant_admin` JWT)
```bash
curl -X POST http://localhost:8080/v1/deals \
  -H "Authorization: Bearer <ADMIN_JWT>" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Hyderabadi Dum Biryani",
    "price_paise": 25000,
    "stock_total": 50,
    "starts_at": "2026-10-01T00:00:00Z",
    "ends_at": "2026-12-31T23:59:59Z"
  }'
```

### 2. Get Deal
`GET /v1/deals/{dealId}` (Public)
```bash
curl http://localhost:8080/v1/deals/<DEAL_ID>
```

### 3. Claim Deal
`POST /v1/deals/{dealId}/claims` (Requires `customer` JWT & `Idempotency-Key`)
```bash
curl -X POST http://localhost:8080/v1/deals/<DEAL_ID>/claims \
  -H "Authorization: Bearer <CUSTOMER_JWT>" \
  -H "Idempotency-Key: claim-req-001" \
  -H "Content-Type: application/json" \
  -d '{"quantity": 1}'
```

### 4. Get Claim
`GET /v1/claims/{claimId}` (Requires `customer` or deal's `restaurant_admin` JWT)
```bash
curl http://localhost:8080/v1/claims/<CLAIM_ID> \
  -H "Authorization: Bearer <CUSTOMER_JWT>"
```

### 5. Payment Webhook
`POST /v1/webhooks/payments` (Requires `X-Payment-Signature: t=...,v1=...`)
```bash
curl -X POST http://localhost:8080/v1/webhooks/payments \
  -H "X-Payment-Signature: t=1791828000,v1=5d41402abc4b2a76b9719d911017c592" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": "evt_101",
    "type": "payment.succeeded",
    "claim_id": "<CLAIM_ID>",
    "amount_paise": 25000,
    "occurred_at": "2026-10-17T00:00:00Z"
  }'
```

---

## 🧪 Concurrency Test Suites

Located at `src/test/kotlin/com/nnomm/concurrency/ConcurrencySuiteTest.kt`:

| Test Suite | Scenario Tested | Result |
| :--- | :--- | :--- |
| **Scenario 1** | 200 concurrent claims fighting for 50 units | **PASSED** (50 successes, 150 sold out, stock reaches 0) |
| **Scenario 2** | 20 concurrent requests with identical `Idempotency-Key` | **PASSED** (1 claim created, all receive identical ID, 1 stock deduction) |
| **Scenario 3** | Concurrent claims by same customer with different keys | **PASSED** (1 success, 9 rejected with `409 ALREADY_CLAIMED`) |
| **Scenario 4** | Webhook edge cases (bad signature, stale timestamp >300s, duplicates) | **PASSED** (Strict 401s, duplicate event idempotent 200) |
| **Scenario 5** | Simultaneous race between payment webhook and expiry worker | **PASSED** (Atomic serialization: ends `PAID` or `REFUND_PENDING`, stock preserved) |
| **Scenario 6** | Anti-enumeration visibility isolation | **PASSED** (Returns `404 NOT_FOUND` for unauthorized customers and admins) |

---

## 🤖 AI Tools Disclosure & Design Decisions

In compliance with the assignment disclosure policy:

- **AI Tools Utilized**: DeepMind Antigravity AI Pair Programmer.
- **Accepted Suggestions**:
  - Flyway migration strategy and PostgreSQL partial unique index design for active claims.
  - Integration of Testcontainers (`PostgreSQLContainer`) for zero-mock, real database concurrency testing.
  - Standardized JSON error response envelope adhering to RFC 7807 specifications.
- **Explicitly Rejected Suggestions**:
  1. **Rejected In-Memory Kotlin `Mutex`**: An AI suggestion proposed using Kotlin coroutine `Mutex` to serialize stock checks. **Rejected** because in-memory synchronization is completely invalid in a distributed, multi-node architecture (`app-instance-1` and `app-instance-2`). Replaced with atomic PostgreSQL conditional update statements.
  2. **Rejected Redis Redlock**: An AI recommendation suggested using Redis distributed locks via Redlock. **Rejected** due to well-documented split-brain risks under asynchronous network partitions (Martin Kleppmann critique) and unnecessary operational complexity. Implemented PostgreSQL row-level locks (`SELECT ... FOR UPDATE` and `SKIP LOCKED`), providing transactional ACID consistency inside the same database boundary.
  3. **Rejected Naive `SELECT` Before `INSERT` for Idempotency**: An AI suggestion proposed querying the table first before inserting an `IN_PROGRESS` idempotency key. **Rejected** because two concurrent requests hitting at the same millisecond would both find no record and attempt to insert. Replaced with atomic `INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING` and transactional lock re-acquisition.
