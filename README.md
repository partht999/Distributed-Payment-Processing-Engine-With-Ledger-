<div align="center">

# 💳 Distributed Payment Processing Engine

### Production-Grade Fintech Backend with Immutable Ledger

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

> A backend system modeling how **Stripe**, **Razorpay**, and **Adyen** process money — featuring immutable double-entry ledgering, three-layer idempotency, distributed locking, event-driven architecture via transactional outbox pattern, and HMAC-signed webhook delivery.

**[Explore the API »](http://localhost:8080/swagger-ui/index.html)** · **[Report Bug](https://github.com/partht999/Distributed-Payment-Processing-Engine-With-Ledger-/issues)**

---

</div>

## 🏗️ System Architecture

```mermaid
graph TB
    subgraph CLIENT["Client Layer"]
        MA["Merchant App"]
        API["REST API Client"]
    end

    subgraph GATEWAY["API Gateway — Spring Boot"]
        PC["PaymentController"]
        WC["WalletController"]
        HC["HealthController"]
    end

    subgraph CORE["Core Services"]
        PS["PaymentService — Create, Authorize, Capture, Refund, Reverse, Retry"]
        WS["WalletService — Transfer, Deposit, Withdraw, Balance"]
    end

    subgraph SAFETY["Safety Layer"]
        RI["Redis Idempotency — SET NX EX"]
        RL["Redis Distributed Lock — Wallet-level"]
        RM["Redis Processing Marker — Dedup guard"]
        DB_IK["DB UNIQUE Constraint — Ultimate safety net"]
        SM["State Machine — 8-state PaymentStatus"]
    end

    subgraph EVENT["Event Pipeline — Transactional Outbox"]
        OEP["OutboxEventPublisher — Same @Transactional"]
        OPS["OutboxPollerService — Scheduled every 5s"]
    end

    subgraph KAFKA["Apache Kafka — KRaft Mode"]
        KT["Topic: payment-events"]
    end

    subgraph CONSUMER["Event Consumer"]
        PEC["PaymentEventConsumer — @KafkaListener"]
        CI["Consumer Idempotency — processed_events table"]
        WH["WebhookService — HMAC-SHA256 signed POST"]
    end

    subgraph DATA["Persistence"]
        PG[("PostgreSQL 15 — ACID Source of Truth")]
        RD[("Redis 7 — Cache + Locks")]
        FW["Flyway — 6 Versioned Migrations"]
    end

    subgraph LEDGER["Immutable Ledger"]
        LE["LedgerEntry — Append-only DEBIT/CREDIT"]
        TXL["Transaction Log — TRANSFER, DEPOSIT, WITHDRAW"]
    end

    subgraph SCHEDULER["Background Jobs"]
        RS["PaymentRetryScheduler — Auto-retry failed payments"]
    end

    MA --> PC
    API --> WC
    API --> HC
    PC --> PS
    WC --> WS
    PS --> RI
    PS --> RL
    PS --> RM
    PS --> DB_IK
    PS --> SM
    PS --> WS
    PS --> OEP
    WS --> LE
    WS --> TXL
    OEP --> PG
    OPS --> PG
    OPS --> KT
    KT --> PEC
    PEC --> CI
    PEC --> WH
    LE --> PG
    TXL --> PG
    FW --> PG
    RS --> PS
    RI --> RD
    RL --> RD
    RM --> RD

    style CLIENT fill:#1a1a2e,stroke:#e94560,color:#fff
    style GATEWAY fill:#16213e,stroke:#0f3460,color:#fff
    style CORE fill:#0f3460,stroke:#533483,color:#fff
    style SAFETY fill:#533483,stroke:#e94560,color:#fff
    style EVENT fill:#2d4059,stroke:#ea5455,color:#fff
    style KAFKA fill:#1a1a2e,stroke:#f07b3f,color:#fff
    style CONSUMER fill:#16213e,stroke:#0f3460,color:#fff
    style DATA fill:#1a1a2e,stroke:#e94560,color:#fff
    style LEDGER fill:#16213e,stroke:#0f3460,color:#fff
    style SCHEDULER fill:#0f3460,stroke:#533483,color:#fff
```

---

## 🔄 Payment Lifecycle State Machine

Every payment transitions through a strict state machine — **no invalid transitions are possible**.

```mermaid
stateDiagram-v2
    [*] --> CREATED: POST /payments
    CREATED --> AUTHORIZED: authorize()
    CREATED --> CAPTURED: process() P2P
    CREATED --> RETRYING: process() fails
    CREATED --> EXPIRED: timeout 2min

    AUTHORIZED --> CAPTURED: capture()
    AUTHORIZED --> REVERSED: reverse()

    RETRYING --> CAPTURED: retry succeeds
    RETRYING --> RETRYING: retry fails < max
    RETRYING --> FAILED: retryCount >= 3

    CAPTURED --> REFUNDED: refund()

    FAILED --> [*]
    EXPIRED --> [*]
    REFUNDED --> [*]
    REVERSED --> [*]
```

---

## ✨ Key Features

| Feature | Description |
|:--------|:------------|
| **Payment Lifecycle** | Create → Authorize → Capture → Refund → Reverse with strict state machine guards |
| **Immutable Double-Entry Ledger** | Append-only DEBIT/CREDIT bookkeeping — balance derived from history, never blindly stored |
| **Three-Layer Idempotency** | Redis SET NX (fast) → DB UNIQUE constraint (safe) → App-level check (defensive) |
| **Distributed Locking** | Redis-based wallet-level locks prevent concurrent double-spend |
| **Transactional Outbox Pattern** | Events saved atomically with payment state — zero event loss even if Kafka is down |
| **Kafka Event Streaming** | KRaft-mode Kafka with key-based partitioning for ordered event delivery |
| **Consumer Idempotency** | `processed_events` table deduplicates Kafka's at-least-once delivery |
| **HMAC-SHA256 Webhooks** | Merchant notifications with cryptographic signature verification |
| **Graceful Degradation** | Redis down? System falls back to DB-only mode. Kafka down? Events wait in outbox |
| **Automated Retry** | Background scheduler retries failed payments with max-retry guard |
| **Merchant Auth+Capture** | Two-phase authorization flow for merchant integrations |
| **Payment Expiry** | Auto-expire stale CREATED payments after 2-minute window |
| **Flyway Migrations** | 6 version-controlled SQL migration scripts |
| **Swagger/OpenAPI** | Interactive API documentation via SpringDoc |
| **Docker Compose** | One-command infrastructure: PostgreSQL + Redis + Kafka |

---

## 🛡️ Reliability Patterns

### Idempotency — Zero Duplicate Charges

```
Request 1: POST /api/v1/payments  { idempotencyKey: "abc-123" }
Response:  201 Created → Payment #42

          ⚡ Network timeout — client retries...

Request 2: POST /api/v1/payments  { idempotencyKey: "abc-123" }  ← same key
Response:  201 Created → Payment #42  ← SAME payment, NOT duplicated
```

**Three layers of protection:**
1. **Redis** — `SET idempotency:abc-123 NX EX 300` — sub-millisecond duplicate detection
2. **PostgreSQL** — `UNIQUE` constraint on `idempotency_key` — permanent safety net
3. **Application** — `findByIdempotencyKey()` check — defensive coding

### Transactional Outbox — Zero Event Loss

```
┌─────────────────────────────────────────────────────────────────┐
│  @Transactional                                                 │
│  paymentRepo.save(payment)     ← DB write #1                   │
│  outboxRepo.save(outboxEvent)  ← DB write #2 (SAME transaction)│
│  COMMIT                        ← Both succeed or both fail     │
└─────────────────────────────────────────────────────────────────┘
         │
         ▼  Every 5 seconds (OutboxPollerService)
┌─────────────────────────────────────────────────────────────────┐
│  SELECT * FROM outbox_events WHERE status = 'PENDING'           │
│  kafkaTemplate.send("payment-events", payload).join()           │
│  event.markPublished()                                          │
└─────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────┐
│  @KafkaListener → PaymentEventConsumer                          │
│  Check processed_events (idempotency) → WebhookService.dispatch │
└─────────────────────────────────────────────────────────────────┘
```

### Graceful Degradation

| Component Down | System Behavior |
|:--------------|:----------------|
| **Redis** | Falls back to DB UNIQUE constraint for idempotency. Skips distributed lock (DB guards still active). Logs WARN. |
| **Kafka** | Events stay safely in `outbox_events` table (status=PENDING). Poller retries on next cycle. |
| **Webhook endpoint** | Delivery failure logged. Consumer still marks Kafka offset. |

---

## 📒 Ledger Design — The Financial Source of Truth

The ledger is **append-only** and **immutable**. No entry is ever updated or deleted.

```mermaid
graph LR
    subgraph TRANSFER["Transfer: Wallet A → Wallet B — ₹500"]
        D["DEBIT — Wallet A: -500"]
        C["CREDIT — Wallet B: +500"]
    end

    subgraph REFUND["Refund: Wallet B → Wallet A — ₹500"]
        D2["DEBIT — Wallet B: -500"]
        C2["CREDIT — Wallet A: +500"]
    end

    D --> C
    D2 --> C2

    style D fill:#e74c3c,color:#fff
    style C fill:#2ecc71,color:#fff
    style D2 fill:#e74c3c,color:#fff
    style C2 fill:#2ecc71,color:#fff
```

**Why immutable ledgering?**

| Traditional Approach ❌ | This Engine ✅ |
|:------------------------|:--------------| 
| `UPDATE wallet SET balance = balance - 100` | Append `DEBIT` entry, derive balance from history |
| History is lost on update | Every financial event is permanently recorded |
| Silent corruption possible | Corrections happen via compensating entries |
| Cannot reconstruct past state | Full audit trail — balance at any point in time |

**Invariants:**
- Every transfer creates exactly **one DEBIT + one CREDIT** (balanced)
- No ledger entry is ever modified after commit
- Every entry links to a `paymentId` for traceability
- Wallet balance is **derived** from ledger sum, never blindly stored

---

## 🗂️ Database Schema

```mermaid
erDiagram
    WALLETS {
        bigint wallet_id PK
        bigint balance
        bigint user_id
        bigint phone_number
        varchar status "ACTIVE | BLOCKED | CLOSED"
    }

    PAYMENTS {
        bigint payment_id PK
        bigint from_wallet_id FK
        bigint to_wallet_id FK
        bigint amount
        varchar status "CREATED | AUTHORIZED | CAPTURED | ..."
        varchar idempotency_key UK
        varchar merchant_order_id
        varchar payment_type "P2P | MERCHANT"
        int retry_count
        int max_retries
        bigint created_at
    }

    LEDGER_ENTRIES {
        bigint entry_id PK
        bigint wallet_id FK
        bigint amount
        varchar entry_type "DEBIT | CREDIT"
        bigint payment_id FK
    }

    TRANSACTIONS {
        bigint transaction_id PK
        bigint from_wallet_id FK
        bigint to_wallet_id FK
        bigint amount
        varchar type "TRANSFER | DEPOSIT | WITHDRAW"
        bigint payment_id FK
    }

    OUTBOX_EVENTS {
        bigint id PK
        varchar event_id UK
        varchar event_type
        varchar aggregate_type
        bigint aggregate_id
        text payload
        varchar status "PENDING | PUBLISHED | FAILED"
        timestamp created_at
        timestamp published_at
    }

    WEBHOOK_CONFIGS {
        bigint id PK
        bigint wallet_id UK
        varchar webhook_url
        varchar secret "HMAC-SHA256 key"
        boolean active
    }

    PROCESSED_EVENTS {
        bigint id PK
        varchar event_id UK
        varchar event_type
        timestamp processed_at
    }

    WALLETS ||--o{ PAYMENTS : "sends/receives"
    WALLETS ||--o{ LEDGER_ENTRIES : "has entries"
    WALLETS ||--o{ TRANSACTIONS : "participates in"
    WALLETS ||--o| WEBHOOK_CONFIGS : "webhook registration"
    PAYMENTS ||--o{ LEDGER_ENTRIES : "generates"
    PAYMENTS ||--o{ TRANSACTIONS : "creates"
    PAYMENTS ||--o{ OUTBOX_EVENTS : "publishes events"
```

---

## 🔌 API Reference

### 💰 Payment APIs

| Method | Endpoint | Description |
|:------:|:---------|:------------|
| `POST` | `/api/v1/payments` | Create a new payment |
| `GET` | `/api/v1/payments/{id}` | Get payment by ID |
| `GET` | `/api/v1/payments` | List all payments |
| `POST` | `/api/v1/payments/{id}/process` | Process (execute) a payment |
| `POST` | `/api/v1/payments/{id}/authorize` | Authorize merchant payment |
| `POST` | `/api/v1/payments/{id}/capture` | Capture authorized payment |
| `POST` | `/api/v1/payments/{id}/refund` | Refund captured payment |
| `POST` | `/api/v1/payments/{id}/reverse` | Reverse authorized payment |

### 👛 Wallet APIs

| Method | Endpoint | Description |
|:------:|:---------|:------------|
| `POST` | `/api/v1/wallets` | Create wallet |
| `GET` | `/api/v1/wallets/{id}` | Get wallet details |
| `GET` | `/api/v1/wallets/{id}/balance` | Get wallet + ledger-derived balance |
| `POST` | `/api/v1/wallets/{id}/deposit` | Deposit funds |
| `POST` | `/api/v1/wallets/{id}/withdraw` | Withdraw funds |
| `GET` | `/api/v1/wallets` | List all wallets |

### 🏥 Health

| Method | Endpoint | Description |
|:------:|:---------|:------------|
| `GET` | `/api/v1/health` | Health check with infrastructure status |

> 📖 **Interactive docs:** [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)

---

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Docker & Docker Compose
- Git

### 1. Clone & Start Infrastructure

```bash
git clone https://github.com/partht999/Distributed-Payment-Processing-Engine-With-Ledger-.git
cd Distributed-Payment-Processing-Engine-With-Ledger-/payment_engine

# Start PostgreSQL + Redis + Kafka
docker compose up -d
```

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

### 3. Test the Full Pipeline

```bash
# Create wallets
curl -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"walletId": 1, "balance": 10000, "userId": 101}'

curl -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"walletId": 2, "balance": 5000, "userId": 102}'

# Create & process a P2P payment
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"fromWalletId": 1, "toWalletId": 2, "amount": 500, "idempotencyKey": "txn-001", "paymentType": "P2P"}'

curl -X POST http://localhost:8080/api/v1/payments/1/process

# Verify ledger-derived balance
curl http://localhost:8080/api/v1/wallets/1/balance

# Test idempotency — same key returns same payment
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"fromWalletId": 1, "toWalletId": 2, "amount": 500, "idempotencyKey": "txn-001", "paymentType": "P2P"}'
# Returns SAME payment — no duplicate charge!
```

### 4. Run Tests

```bash
./mvnw test
```

---

## 📁 Project Structure

```
payment_engine/
├── docker-compose.yml                    # PostgreSQL + Redis + Kafka (KRaft)
├── pom.xml                               # Maven + Spring Boot 4.0
└── src/
    ├── main/java/com/distributed/payment_engine/
    │   ├── PaymentEngineApplication.java
    │   ├── config/
    │   │   ├── FlywayConfig.java           # Flyway migration config
    │   │   ├── OpenApiConfig.java          # Swagger/OpenAPI setup
    │   │   └── RedisConfig.java            # Redis connection + pool
    │   ├── controller/
    │   │   ├── HealthController.java       # Health check endpoint
    │   │   ├── PaymentController.java      # Payment REST API
    │   │   └── WalletController.java       # Wallet REST API
    │   ├── service/
    │   │   ├── PaymentService.java         # Core payment orchestration (437 lines)
    │   │   ├── WalletService.java          # Transfers + ledger entries
    │   │   ├── RedisIdempotencyService.java      # Redis SET NX idempotency
    │   │   ├── RedisDistributedLockService.java  # Wallet-level locks
    │   │   ├── RedisProcessingMarkerService.java # Dedup processing guard
    │   │   ├── OutboxEventPublisher.java   # Transactional outbox writer
    │   │   ├── OutboxPollerService.java    # Polls outbox → publishes to Kafka
    │   │   ├── PaymentEventConsumer.java   # @KafkaListener consumer
    │   │   ├── WebhookService.java         # HMAC-signed webhook dispatch
    │   │   ├── PaymentEventPublisher.java  # Publisher interface
    │   │   └── LoggingEventPublisher.java  # Fallback publisher
    │   ├── model/
    │   │   ├── entity/                     # 7 JPA entities
    │   │   ├── dto/                        # 6 request/response DTOs
    │   │   ├── enums/                      # 8 status/type enums
    │   │   └── event/                      # PaymentEvent domain event
    │   ├── repository/                     # 7 Spring Data JPA repositories
    │   ├── scheduler/
    │   │   └── PaymentRetryScheduler.java  # Automated retry background job
    │   └── exception/                      # Global error handling
    ├── main/resources/
    │   ├── application.properties          # All configuration
    │   └── db/migration/                   # 6 Flyway SQL migrations
    └── test/java/                          # 6 test classes, 42+ test methods
```

---

## 🧪 Test Coverage

| Test Class | What It Tests | # Tests |
|:-----------|:-------------|:-------:|
| `PaymentIntegrationTest` | Full payment lifecycle E2E (create, process, refund, idempotency) | 12 |
| `RedisIntegrationTest` | Redis idempotency, caching, failure handling | 10 |
| `DistributedLockTest` | Concurrent payment protection with Redis locks | 8 |
| `IdempotencyTtlTest` | TTL expiration and key reuse behavior | 6 |
| `ProcessingMarkerTest` | Processing dedup guard behavior | 5 |
| `PaymentEngineApplicationTests` | Spring context loads successfully | 1 |

```bash
# Run all tests (Docker must be running)
./mvnw test

# Run specific test class
./mvnw test -Dtest=PaymentIntegrationTest
```

---

## 🧠 Design Decisions

| Decision | Rationale |
|:---------|:----------|
| **Modular monolith** | Financial correctness over premature microservice splitting |
| **PostgreSQL as source of truth** | ACID guarantees essential for money movement |
| **Redis as cache layer** | Fast idempotency + locking, with graceful degradation when down |
| **Kafka with Outbox** | At-least-once delivery without dual-write risk |
| **KRaft mode** | No ZooKeeper dependency — simpler Kafka deployment |
| **Ledger-derived balance** | Wallet balance column is a cache; ledger is immutable truth |
| **Integer amounts** | Stored in smallest currency unit (paise/cents) — no floating point errors |
| **Enum state machine** | Compile-time safety for payment status transitions |
| **HMAC-SHA256 webhooks** | Same pattern as Stripe/Razorpay — merchant can verify authenticity |
| **`@Transactional` boundaries** | Payment + ledger + outbox event in a single atomic commit |

---

## 🧩 What This Project Demonstrates

<table>
<tr>
<td width="50%">

### 🏛️ System Design
- Distributed systems patterns (outbox, DLQ, idempotency)
- Financial correctness with immutable ledger
- State machine driven payment lifecycle
- Event-driven architecture with Kafka
- Failure-aware design with graceful degradation

</td>
<td width="50%">

### ⚙️ Backend Engineering
- Java 17 + Spring Boot 4.0
- JPA/Hibernate with PostgreSQL
- Spring Data Redis for caching + locking
- Spring Kafka for event streaming
- Flyway for version-controlled migrations

</td>
</tr>
<tr>
<td>

### 🛡️ Reliability Patterns
- Three-layer idempotent API design
- Redis distributed locking (wallet-level)
- Transactional outbox (zero event loss)
- Automated retry with max-retry guard
- Consumer-side deduplication

</td>
<td>

### 📊 Data Engineering
- Immutable double-entry ledger
- Ledger-derived balance computation
- Append-only audit trail
- Integer-based money representation
- 7 normalized tables with proper indexing

</td>
</tr>
</table>

---

<div align="center">

### Built by [partht999](https://github.com/partht999)

⭐ Star this repo if you found it useful!

</div>
