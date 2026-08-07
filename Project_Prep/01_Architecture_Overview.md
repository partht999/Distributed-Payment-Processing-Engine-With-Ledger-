# Part 1: Architecture Overview — How Everything Connects

> Read this first. This is the 30,000-foot view before we zoom into each component.

---

## What Is This Project?

This is a **backend payment processing engine** — the kind of system that powers Stripe, Razorpay, and Adyen. When someone sends money (P2P transfer) or a merchant charges a customer, this engine handles the entire flow:

1. **Create** a payment record
2. **Validate** the payment (sufficient balance, active wallets)
3. **Move money** between wallets using an immutable ledger
4. **Publish events** so other systems (notifications, analytics) know what happened
5. **Send webhooks** to merchants so they know the payment succeeded

---

## The Tech Stack — Why Each Technology

| Technology | Role | Why This, Not Something Else? |
|:-----------|:-----|:------------------------------|
| **Java 17** | Application language | Industry standard for fintech. Strong typing catches bugs at compile time. |
| **Spring Boot 4.0** | Application framework | Auto-configuration, dependency injection, built-in Kafka/Redis/JPA support. |
| **PostgreSQL 15** | Primary database (source of truth) | ACID transactions guarantee money never gets lost. Used by Stripe. |
| **Redis 7** | Cache + locks + idempotency | Sub-millisecond reads for frequent lookups. Distributed locking for concurrency. |
| **Apache Kafka 3.7** | Event streaming | Decouples payment processing from notifications. At-least-once delivery. |
| **Flyway** | Database migrations | Version-controlled SQL. Every schema change is tracked, reversible, and auditable. |
| **Docker Compose** | Infrastructure orchestration | One command boots the entire stack: app + DB + cache + message broker + monitoring. |
| **Prometheus + Grafana** | Monitoring & dashboards | Industry standard for metrics collection and visualization. |

---

## System Architecture — The Full Picture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                                      │
│  (Merchant App, REST Client, Mobile App)                                 │
│  Sends HTTP requests to our REST API                                     │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    CONTROLLER LAYER (REST API)                           │
│                                                                          │
│  PaymentController     → POST/GET /api/v1/payments                       │
│  WalletController      → POST/GET /api/v1/wallets                        │
│  ReconciliationController → GET /api/v1/reconciliation                   │
│  HealthController      → GET /api/v1/health                              │
│                                                                          │
│  ROLE: Accept HTTP requests, validate input, delegate to services.       │
│  NO business logic here — just routing.                                  │
└──────────────────┬──────────────────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     SERVICE LAYER (Business Logic)                        │
│                                                                          │
│  ┌─────────────────┐    ┌────────────────┐    ┌────────────────────┐    │
│  │ PaymentService   │───▶│ WalletService  │───▶│ LedgerRepository   │    │
│  │ (436 lines)      │    │ (197 lines)    │    │ TransactionRepo    │    │
│  │                  │    │                │    │ WalletRepo         │    │
│  │ • createPayment  │    │ • transfer()   │    └────────────────────┘    │
│  │ • processPayment │    │ • deposit()    │                              │
│  │ • authorizePaymt │    │ • withdraw()   │                              │
│  │ • capturePayment │    │ • getWallet()  │                              │
│  │ • refundPayment  │    │ • getBalance() │                              │
│  │ • reversePayment │    └────────────────┘                              │
│  └────────┬─────────┘                                                    │
│           │                                                              │
│  ┌────────▼─────────────────────────────────────────────────────┐        │
│  │              SAFETY LAYER (Reliability)                       │        │
│  │                                                               │        │
│  │  RedisIdempotencyService     → Prevents duplicate payments    │        │
│  │  RedisDistributedLockService → Prevents double-spend          │        │
│  │  RedisProcessingMarkerService → Prevents concurrent process   │        │
│  │  DB UNIQUE constraint        → Ultimate safety net            │        │
│  │  State Machine guards        → Prevents invalid transitions   │        │
│  └───────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────┐        │
│  │              EVENT LAYER (Transactional Outbox)               │        │
│  │                                                               │        │
│  │  OutboxEventPublisher → Saves event in SAME DB transaction    │        │
│  │  OutboxPollerService  → Polls DB every 5s, publishes to Kafka │        │
│  │  PaymentEventConsumer → @KafkaListener, processes events      │        │
│  │  WebhookService       → HMAC-signed HTTP POST to merchants    │        │
│  │  DeadLetterPublisher  → Parks failed messages in DLQ          │        │
│  └──────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────┐        │
│  │              BACKGROUND JOBS                                  │        │
│  │                                                               │        │
│  │  PaymentRetryScheduler → Retries RETRYING payments every 30s  │        │
│  │  ReconciliationService → Verifies ledger integrity on-demand  │        │
│  └──────────────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      DATA LAYER                                          │
│                                                                          │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────────────┐     │
│  │ PostgreSQL   │  │ Redis         │  │ Apache Kafka              │     │
│  │              │  │               │  │                           │     │
│  │ 7 tables:    │  │ 3 key types:  │  │ Topics:                   │     │
│  │ • wallets    │  │ • idempotency │  │ • payment-events          │     │
│  │ • payments   │  │ • lock:wallet │  │ • payment-events-dlq      │     │
│  │ • ledger     │  │ • processing  │  │                           │     │
│  │ • txns       │  │               │  │ Consumer group:            │     │
│  │ • outbox     │  │ All have TTL  │  │ • payment-engine-group    │     │
│  │ • webhooks   │  │ (auto-expire) │  │                           │     │
│  │ • processed  │  └───────────────┘  └──────────────────────────┘     │
│  └──────────────┘                                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## How a Payment Flows Through the System

### Example: User sends ₹500 from Wallet 1 to Wallet 2

```
Step 1: CLIENT sends POST /api/v1/payments
        Body: { fromWalletId: 1, toWalletId: 2, amount: 500,
                idempotencyKey: "txn-001", paymentType: "P2P" }

Step 2: PaymentController receives the request
        → Calls paymentService.createPayment(...)

Step 3: PaymentService.createPayment()
        a) Check Redis: "Does idempotency:txn-001 exist?" → NO
        b) Check PostgreSQL: "SELECT * WHERE idempotency_key = 'txn-001'" → NOT FOUND
        c) INSERT payment into payments table (status = CREATED)
        d) SET idempotency:txn-001 in Redis (with 5-min TTL)
        e) Save outbox event (PAYMENT_CREATED) in SAME transaction
        f) COMMIT → Both payment and outbox event saved atomically
        g) Return Payment object (status = CREATED)

Step 4: CLIENT sends POST /api/v1/payments/1/process
        → Calls paymentService.processPayment(1)

Step 5: PaymentService.processPayment()
        a) SET processing:payment:1 in Redis (marker: "this payment is being processed")
        b) Load payment from DB → status is CREATED → valid for processing
        c) Check expiry: createdAt + 2min > now? → Not expired
        d) Acquire wallet lock: SET lock:wallet:1 {uuid} NX EX 30
        e) Call walletService.transfer(1, 2, 500, paymentId)

Step 6: WalletService.transfer()
        a) Load Wallet 1 and Wallet 2 from DB
        b) Check: Wallet 1 active? ✅  Wallet 2 active? ✅
        c) Check: ledgerDerivedBalance(1) >= 500? ✅
        d) Wallet 1 balance: 10000 - 500 = 9500 → SAVE
        e) Wallet 2 balance: 5000 + 500 = 5500 → SAVE
        f) Insert Transaction record (TRANSFER, paymentId)
        g) Insert LedgerEntry: DEBIT, Wallet 1, ₹500, paymentId
        h) Insert LedgerEntry: CREDIT, Wallet 2, ₹500, paymentId
        i) Return true

Step 7: Back in PaymentService
        a) Set payment status → CAPTURED
        b) SAVE payment
        c) Publish PAYMENT_CAPTURED event → OutboxEventPublisher
           (saved in outbox_events table, same transaction)
        d) COMMIT everything
        e) Release wallet lock (Lua script: check UUID, then DEL)
        f) Clear processing marker

Step 8: [5 seconds later] OutboxPollerService wakes up
        a) SELECT * FROM outbox_events WHERE status = 'PENDING'
        b) Found 1 event → publish to Kafka topic "payment-events"
        c) Kafka key = paymentId (ensures ordering per payment)
        d) Mark event as PUBLISHED in DB

Step 9: PaymentEventConsumer receives the Kafka message
        a) Check processed_events table: "Did I already process this?"
        b) NO → Extract toWalletId (2) from the event
        c) Call webhookService.dispatch(2, eventJson)
        d) WebhookService: Lookup webhook_configs for wallet 2
           → If registered: compute HMAC-SHA256, POST to merchant URL
           → If not registered: skip (no-op)
        e) Insert record into processed_events (idempotency)

DONE! ₹500 moved from Wallet 1 to Wallet 2.
```

---

## The 7 Database Tables

| Table | Purpose | Key Columns |
|:------|:--------|:------------|
| `wallets` | User wallet accounts | walletId, balance (cache), userId, status |
| `payments` | Payment records | paymentId, fromWalletId, toWalletId, amount, status, idempotencyKey |
| `ledger_entries` | Immutable financial log | entryId, walletId, amount, entryType (DEBIT/CREDIT), paymentId |
| `transactions` | Transaction log | transactionId, fromWalletId, toWalletId, amount, type (TRANSFER/DEPOSIT/WITHDRAW) |
| `outbox_events` | Pending events for Kafka | id, eventId, payload, status (PENDING/PUBLISHED/FAILED) |
| `webhook_configs` | Merchant webhook URLs | walletId, webhookUrl, secret (HMAC key), active |
| `processed_events` | Consumer idempotency | eventId, eventType, processedAt |

---

## The 13 Service Classes — What Each Does

| # | Service | Lines | Responsibility |
|:-:|:--------|:-----:|:--------------|
| 1 | **PaymentService** | 436 | Core orchestrator. Creates, processes, authorizes, captures, refunds, reverses payments. |
| 2 | **WalletService** | 197 | Wallet operations: create, transfer, deposit, withdraw. Writes ledger entries. |
| 3 | **RedisIdempotencyService** | 196 | Redis SET NX — prevents duplicate payment creation. |
| 4 | **RedisDistributedLockService** | 182 | Wallet-level locks — prevents double-spend across servers. |
| 5 | **RedisProcessingMarkerService** | 143 | Payment-level marker — prevents same payment processed twice. |
| 6 | **OutboxEventPublisher** | 93 | Saves events to outbox table (same transaction as payment). |
| 7 | **OutboxPollerService** | 117 | Polls outbox every 5s, publishes to Kafka. |
| 8 | **PaymentEventConsumer** | 208 | @KafkaListener — processes events, dispatches webhooks, idempotent. |
| 9 | **WebhookService** | 133 | HMAC-SHA256 signed HTTP POST to merchant webhook URLs. |
| 10 | **DeadLetterPublisher** | 102 | Routes permanently-failed messages to DLQ topic. |
| 11 | **ReconciliationService** | 225 | Verifies ledger integrity: double-entry balance, wallet vs ledger, orphan detection. |
| 12 | **PaymentEventPublisher** | — | Interface that defines `publish(PaymentEvent)`. |
| 13 | **LoggingEventPublisher** | — | Fallback publisher that just logs events. |

---

## Key Design Principle: Separation of Concerns

```
Controller → "WHAT endpoint was called?"
Service    → "HOW do we process this business logic?"
Repository → "WHERE do we store the data?"
Config     → "HOW are things configured?"
Exception  → "WHAT went wrong and how to tell the client?"
Scheduler  → "WHEN should background jobs run?"
```

Every class has ONE job. PaymentService doesn't know about HTTP status codes. WalletController doesn't know about ledger entries. This is called the **Single Responsibility Principle**.

---

## Next: Read Part 2 to understand the Payment Lifecycle and State Machine.
