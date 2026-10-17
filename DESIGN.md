# Nnomm Flash-Deal Architecture & System Design Document

This document answers the core architecture, scalability, concurrency, and production readiness questions for the Nnomm high-concurrency flash-deal engine.

---

## 1. Scaling to 100,000 Requests Per Second

### The Bottlenecks in the Current Architecture
At 100,000 requests per second:
1. **Single-Row Lock Contention**: When 100,000 requests attempt to claim the same deal in a single second, all 100k transactions queue up trying to acquire an exclusive row lock on that single `deals` row in PostgreSQL. Row lock waiting and transaction overhead collapse throughput.
2. **Database Connection Pool Exhaustion**: Even with HikariCP, connection pools are bound by physical database CPU/disk limits (typically 50–200 active connections). 100k concurrent HTTP threads would saturate the pool, resulting in connection timeouts.
3. **Write-Ahead Log (WAL) Flush Latency**: Each committed transaction must synchronously flush to disk. Disk IOPS will bottleneck regardless of hardware.

### Redesigned Architecture for 100,000 req/s

```
                          ┌───────────────────────┐
                          │    Edge CDN / LB      │
                          │(Cloudflare / Fastly)  │
                          └───────────┬───────────┘
                                      │
                   ┌──────────────────┴──────────────────┐
                   ▼                                     ▼
        ┌─────────────────────┐               ┌─────────────────────┐
        │  Read Path (95%)    │               │  Claim Path (5%)    │
        │  GET /v1/deals/{id} │               │  POST /claims       │
        └──────────┬──────────┘               └──────────┬──────────┘
                   │                                     │
                   ▼                                     ▼
        ┌─────────────────────┐               ┌─────────────────────┐
        │ Redis Read Replicas │               │ API Gateway Cluster │
        │ + Local L1 Cache    │               │ (Token Bucket Rate) │
        └─────────────────────┘               └──────────┬──────────┘
                                                         │
                                                         ▼
                                              ┌─────────────────────┐
                                              │ Redis Cluster (Lua) │
                                              │ Atomic Stock Decr   │
                                              └──────────┬──────────┘
                                                         │
                                                         ▼
                                              ┌─────────────────────┐
                                              │ Kafka / Pulsar Topic│
                                              │ (Key = deal_id)     │
                                              └──────────┬──────────┘
                                                         │
                                                         ▼
                                              ┌─────────────────────┐
                                              │ Async Batch Writers │
                                              │ (PostgreSQL Pool)   │
                                              └─────────────────────┘
```

1. **Read Path Separation**:
   - Cache deal details at the CDN edge with short TTLs (1–2 seconds) and `stale-while-revalidate`.
   - Internal microservices read from Redis Read Replicas combined with an in-memory Caffeine L1 cache.

2. **In-Memory Inventory Token Bucket (Redis + Lua Script)**:
   - When a deal is launched, initialize its available stock in a Redis Cluster: `SET deal:stock:<id> 500`.
   - Claims execute an atomic Lua script:
     ```lua
     local stock = redis.call('get', KEYS[1])
     if not stock or tonumber(stock) < tonumber(ARGV[1]) then
         return 0 -- SOLD OUT
     end
     redis.call('decrby', KEYS[1], ARGV[1])
     return 1 -- RESERVATION GRANTED
     ```
   - Redis evaluates Lua scripts atomically in single-threaded memory loops at 120,000+ ops/sec per node without disk I/O blocking.
   - Once stock reaches 0, all subsequent incoming requests are rejected immediately at the application layer in sub-millisecond time without touching the database.

3. **Asynchronous Batch Settlement via Kafka**:
   - Requests that secure a stock reservation token publish a `ClaimGranted` event to an Apache Kafka topic, partitioned by `deal_id`.
   - Batch consumer workers pull events from Kafka and execute batched SQL multi-row inserts (`INSERT INTO claims VALUES (...), (...);`) into PostgreSQL in ordered chunks of 500 records.

---

## 2. Database Row Locks vs. Distributed Locks (Redis Redlock)

### Comparison Matrix

| Factor | Database Row Locks (`SELECT FOR UPDATE`) | Distributed Locks (`Redis Redlock`) |
| :--- | :--- | :--- |
| **Consistency / Safety** | **Strict ACID serializability**. Handled inside PostgreSQL MVCC engine. | **Probabilistic safety**. Vulnerable to clock drift, GC pauses, and network partitions. |
| **Split-Brain Risk** | **None**. Single authority for data and locks. | **High risk** under asynchronous replication or quorum failures (Martin Kleppmann critique). |
| **Network Round-trips** | Integrated directly into the SQL transaction. | Requires multiple roundtrips to $N$ independent Redis masters to acquire/release locks. |
| **Deadlock Handling** | PostgreSQL provides built-in deadlock detection and automatic transaction rollback. | Client-side expiration (TTL leases). If worker takes longer than TTL, lock is silently released. |
| **Operational Overhead** | Zero additional infrastructure. | Requires running 5 independent Redis master nodes and managing lease renewals/fencing tokens. |

### Why We Chose PostgreSQL Row-Level Locks
For high-concurrency flash deals with financial implications:
1. **Fencing & Lease Invalidation Hazards**: In Redlock, if an application instance experiences a Stop-The-World (STW) JVM garbage collection pause or network delay while holding a lock, the Redis lease expires. Another instance acquires the lock. When the first instance wakes up, it proceeds to commit, causing **double claiming and inventory overselling**.
2. **Atomic In-Database Evaluation**: In PostgreSQL, `UPDATE deals SET stock_available = stock_available - ? WHERE id = ? AND stock_available >= ?` does not even require an explicit lock statement; it locks and decrements in one step and releases upon commit.
3. **Guaranteed Consistency**: By combining PostgreSQL conditional updates with partial unique indexes, zero overselling is mathematically guaranteed by the database engine.

---

## 3. Database Partitioning & Sharding at 100x Scale

When expanding to hundreds of thousands of restaurants and millions of concurrent deals:

### 1. Sharding Strategy: Partitioning by `restaurant_id`
- **Choice of Key**: Shard primarily by `hash(restaurant_id)`.
- **Rationale**:
  - Deals, claims, and menus are inherently restaurant-scoped.
  - Transactions that claim a deal only need to read and update data belonging to that specific restaurant.
  - Colocating `deals` and `claims` with the same `restaurant_id` on the same physical shard ensures that stock decrements and claim reservations remain **single-shard transactions**, completely avoiding distributed transactions (2-Phase Commit / XA).

### 2. Table Partitioning Inside Shards (Declarative Partitioning)
- **`claims` Table**: Partitioned by Range on `created_at` (e.g., monthly or weekly partitions).
  - High-velocity inserts write exclusively to the active partition.
  - Older partitions can be detached or moved to cold storage (e.g. Amazon S3 / BigQuery) without impacting flash-deal write performance.
- **`idempotency_records` Table**: Partitioned by Hash on `customer_id` with a 48-hour automated retention/drop policy.

### 3. Global Secondary Indexes & Cross-Shard Reads
- Customer-facing queries like `GET /v1/customers/me/claims` across multiple restaurants query an aggregated Read Replica cluster or an ElasticSearch / OpenSearch read-model populated via Change Data Capture (Debezium / Kafka Connect).

---

## 4. Deadlock Prevention Strategy

Deadlocks occur when two or more concurrent transactions attempt to acquire locks on the same set of rows in different orders (e.g., Tx 1 locks Deal A then Claim B; Tx 2 locks Claim B then Deal A).

### Deadlock Prevention Guarantees in Nnomm
1. **Strict Global Lock Hierarchy**:
   Every service and worker in the system adheres to a single hierarchical acquisition order:
   $$\text{Parent Deal Row} \longrightarrow \text{Child Claim Row} \longrightarrow \text{Idempotency Record}$$
   - `ClaimService`: Locks deal stock $\rightarrow$ Inserts claim $\rightarrow$ Updates idempotency record.
   - `ClaimExpiryWorker`: Scans and locks claim rows with `SKIP LOCKED` $\rightarrow$ Restores deal stock.
   - `PaymentWebhookService`: Locks claim row with `FOR UPDATE` $\rightarrow$ Updates claim status.

2. **`SELECT ... FOR UPDATE SKIP LOCKED`**:
   The background `ClaimExpiryWorker` uses `SKIP LOCKED`. If an incoming payment webhook is currently locking an expiring claim, the worker does not wait or block; it simply skips that row and proceeds to the next, preventing worker-request lock queues.

3. **Single-Statement Atomic Decrement**:
   We avoid `SELECT ... FOR UPDATE` on `deals` during claims, instead using:
   ```sql
   UPDATE deals 
   SET stock_available = stock_available - ? 
   WHERE id = ? AND stock_available >= ?;
   ```
   This locks the row only for the microseconds required to execute the decrement and commit.

4. **Short Transaction Boundaries**:
   No network I/O, webhook HMAC verification, or Jackson serialization is ever executed inside an active database transaction.

---

## 5. Handling Out-of-Order and Malformed Webhook Deliveries

Payment gateways frequently deliver webhooks out of order, retry dropped connections, or deliver events concurrently across multiple instances.

### 1. Strictly Monotonic State Machine
Claims follow a strictly enforced directed status graph:
```
                      ┌──────────────┐
                      │   RESERVED   │
                      └──────┬───────┘
                             │
            ┌────────────────┼────────────────┐
            ▼                                 ▼
     ┌──────────────┐                  ┌──────────────┐
     │     PAID     │                  │   EXPIRED    │
     └──────────────┘                  └──────┬───────┘
       (Terminal)                             │ (Late Payment)
                                              ▼
                                       ┌──────────────┐
                                       │REFUND_PENDING│
                                       └──────────────┘
                                          (Terminal)
```
- `PAID` and `REFUND_PENDING` are **terminal states**.
- If a duplicate `payment.succeeded` event arrives for a claim already in `PAID`, the system acknowledges `200 OK` without re-processing.
- If a `payment.failed` event arrives for a claim that is already `PAID`, it is rejected as invalid/stale state transition.

### 2. Idempotent Deduplication via `processed_webhooks`
Before executing any business logic:
- The handler inspects `processed_webhooks` for `event_id`.
- If found, it immediately returns `200 OK` (idempotent no-op).
- If not found, it acquires an exclusive lock on the claim, updates the claim, records `event_id` in `processed_webhooks`, and commits in a single transaction.

### 3. Unknown Claim IDs (Delayed Claim Replication)
If a payment webhook arrives before the claim is readable due to replication lag or provider error:
- The system records the event with status `UNKNOWN_CLAIM` in `processed_webhooks` and acknowledges `200 OK`.
- This satisfies the payment gateway's retry loop and prevents retry storms while maintaining an audit trail for reconciliation jobs.

---

## 6. Production Monitoring, Alerting & Observability

### 1. Golden Metrics (Prometheus / Micrometer)
- **Inventory Exhaustion Velocity**:
  - `deal_stock_available{deal_id="..."}`: Gauge tracking remaining units.
  - Rate of change $\Delta(\text{stock})/\Delta t$ alerts operators when flash deals are draining rapidly.
- **P99 Claim Request Latency**:
  - `http_server_requests_seconds{uri="/v1/deals/{id}/claims", status="201"}`.
  - Alert trigger: P99 latency $> 100\text{ms}$ for $> 30\text{ seconds}$.
- **HikariCP Connection Pool Saturation**:
  - `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_idle`.
  - Alert trigger: `pending_connections > 5` for $> 15\text{ seconds}$ indicates database IOPS exhaustion.
- **Lock Contention**:
  - PostgreSQL `pg_stat_activity` tracking queries in `exclusive lock` wait states.
- **Worker Expiry Rate vs. Payment Velocity**:
  - Count of claims transitioning to `REFUND_PENDING` vs `PAID`. An elevated ratio indicates customers are experiencing payment latency exceeding `CLAIM_TTL_SECONDS`.

### 2. Production Health Dashboard Layout
```
┌─────────────────────────────────┬─────────────────────────────────┐
│ Deal Stock Depletion Curve      │ P50 / P95 / P99 HTTP Latency    │
│ (Real-time gauge per deal)      │ (Target P99 < 50ms)             │
├─────────────────────────────────┼─────────────────────────────────┤
│ HikariCP Pool Saturation        │ Claim Outcome Breakdown         │
│ (Active vs Pending Threads)     │ (201 Created vs 409 Sold Out)   │
├─────────────────────────────────┼─────────────────────────────────┤
│ Webhook Ingestion Rate          │ Background Expiry Worker Count  │
│ (Events/sec & Signature Errors) │ (Expired Claims Reaped / sec)   │
└─────────────────────────────────┴─────────────────────────────────┘
```

---

## 7. Architectural Trade-offs & Future Enhancements

### Trade-offs Made in Current Implementation
1. **Direct Database Persistence over Memory-First Architecture**:
   - *Trade-off*: Writing claims directly to PostgreSQL ensures 100% ACID consistency and simplicity, but caps peak throughput at ~3,000–5,000 writes/sec per database server.
   - *Alternative Considered*: Pre-allocating stock in Redis with asynchronous Kafka settlement would support 100k+ req/s, but introduces eventual consistency and complex compensation workflows if Kafka consumers fail.
2. **Periodic Polling Expiry Worker over Delay Queues**:
   - *Trade-off*: Running `ClaimExpiryWorker` every 1,000ms with `SKIP LOCKED` is simple and resilient to node crashes, but introduces up to 1 second of latency before expired stock is returned to the pool.
   - *Alternative Considered*: RabbitMQ dead-letter queues or Redis sorted sets (`ZSET`) offer exact-millisecond expiry notifications, but require distributed coordination to prevent duplicate execution across instances.

### What Would Be Done Differently with More Time
1. **Outbox Pattern for Domain Events**:
   Implement the Transactional Outbox Pattern to publish `DealSoldOut`, `ClaimReserved`, and `PaymentReceived` events to Kafka within the same database transaction.
2. **OpenTelemetry Distributed Tracing**:
   Add W3C trace context propagation across HTTP requests, database queries, and background workers to visualize end-to-end request latency traces in Jaeger/Zipkin.
3. **Optimistic Pre-Validation**:
   Add a fast non-locking read of `stock_available` before opening a transaction to fast-fail `409 SOLD_OUT` claims at zero database cost once a deal has been emptied.
