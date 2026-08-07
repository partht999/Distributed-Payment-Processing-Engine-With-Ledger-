# Part 7: Quick Reference — Cheat Sheet

> Print this or keep it open while preparing. Everything on one page.

---

## Project Name
**Distributed Payment Processing Engine with Immutable Ledger**

## One-Line Pitch
> A production-grade payment backend modeling Stripe/Razorpay — featuring immutable double-entry ledger, three-layer idempotency, distributed locking, transactional outbox pattern, and HMAC-signed webhooks.

---

## Tech Stack (Memorize This)

```
Java 17 + Spring Boot 4.0 + PostgreSQL 15 + Redis 7 + Kafka 3.7 (KRaft)
Flyway (migrations) + Docker Compose + Prometheus + Grafana
```

---

## 10 Key Features (Elevator Pitch)

1. **Immutable Double-Entry Ledger** — Append-only DEBIT/CREDIT, balance derived from history
2. **Three-Layer Idempotency** — Redis SET NX → DB UNIQUE → App check
3. **Distributed Locking** — Redis wallet-level locks with Lua script release
4. **Transactional Outbox** — Events saved in same DB transaction, zero event loss
5. **Kafka Event Streaming** — KRaft mode, key-based partitioning for ordering
6. **HMAC-SHA256 Webhooks** — Cryptographically signed merchant notifications
7. **Consumer Idempotency** — processed_events table prevents double-processing
8. **Dead Letter Queue** — Failed messages parked for investigation, never dropped
9. **Graceful Degradation** — Redis/Kafka down = system still works (DB fallback)
10. **Reconciliation Engine** — Three-check financial integrity verification

---

## 8 Payment States

```
CREATED → AUTHORIZED → CAPTURED → REFUNDED
                ↓
             REVERSED

CREATED → RETRYING → FAILED
            ↓
         CAPTURED

CREATED → EXPIRED
```

---

## 7 Database Tables

```
wallets          — User accounts (walletId, balance, status)
payments         — Payment records (status, idempotencyKey, type)
ledger_entries   — Immutable financial log (DEBIT/CREDIT)
transactions     — Transaction log (TRANSFER/DEPOSIT/WITHDRAW)
outbox_events    — Pending events for Kafka (PENDING/PUBLISHED)
webhook_configs  — Merchant webhook URLs + HMAC secrets
processed_events — Consumer idempotency records
```

---

## 3 Redis Key Types

```
idempotency:{key}         → Prevents duplicate payment creation (TTL: 5min)
lock:wallet:{walletId}    → Prevents concurrent wallet operations (TTL: 30s)
processing:payment:{id}   → Prevents same payment processed twice (TTL: 60s)
```

All use: `SET {key} {value} NX EX {ttl}` — atomic set-if-not-exists with expiry

---

## Event Pipeline Flow

```
PaymentService → OutboxEvent (DB) → Poller (5s) → Kafka → Consumer → Webhook
                 ↑ same txn ↑                              ↓
                                                     DLQ (on failure)
```

---

## Key Code Locations

| Component | File | Lines |
|:----------|:-----|:-----:|
| Payment orchestration | `PaymentService.java` | 436 |
| Money movement + ledger | `WalletService.java` | 197 |
| Redis idempotency | `RedisIdempotencyService.java` | 196 |
| Distributed locks | `RedisDistributedLockService.java` | 182 |
| Processing markers | `RedisProcessingMarkerService.java` | 143 |
| Outbox writer | `OutboxEventPublisher.java` | 93 |
| Outbox → Kafka poller | `OutboxPollerService.java` | 117 |
| Kafka consumer | `PaymentEventConsumer.java` | 208 |
| Webhook dispatch | `WebhookService.java` | 133 |
| Dead letter queue | `DeadLetterPublisher.java` | 102 |
| Reconciliation | `ReconciliationService.java` | 225 |
| Retry scheduler | `PaymentRetryScheduler.java` | 66 |

---

## Design Decisions — Quick Answers

| Decision | Why |
|:---------|:----|
| Modular monolith | ACID transactions for money. Correctness > microservices hype |
| PostgreSQL | ACID source of truth. Used by Stripe. |
| Redis as cache | Fast idempotency + locking. Graceful degradation when down. |
| Kafka + Outbox | At-least-once without dual-write risk |
| Ledger-derived balance | wallet.balance is a cache; ledger is immutable truth |
| Integer amounts | No floating point errors. ₹500 stored as 500 (paise) |
| Lua script for lock release | Prevents accidental release of another server's lock |
| KRaft mode Kafka | No ZooKeeper = simpler deployment |
| Poller (not CDC) | Simpler, debuggable, good enough for 5s latency |

---

## Reading Order for Full Understanding

```
1. 01_Architecture_Overview.md     — How everything connects
2. 02_Payment_Lifecycle.md         — State machine, P2P vs Merchant flows
3. 03_Immutable_Ledger.md          — Double-entry bookkeeping, reconciliation
4. 04_Idempotency_And_Locking.md   — Three layers, distributed locks, markers
5. 05_Event_Driven_Architecture.md — Outbox, Kafka, webhooks, DLQ
6. 06_Interview_Questions.md       — 22 Q&As for interview preparation
7. 07_Quick_Reference.md           — This file (cheat sheet)
```

---

## Resume Bullet Point

> Built a **Distributed Payment Processing Engine** (Java 17, Spring Boot 4.0) with immutable double-entry ledger, three-layer idempotency (Redis + PostgreSQL), transactional outbox pattern for Kafka event streaming, distributed wallet-level locking, HMAC-signed webhooks, and automated reconciliation — demonstrating Stripe/Razorpay-level reliability patterns.

---

## Demo Script (30-second version)

```bash
# 1. Start infra
docker compose up -d

# 2. Create wallets
curl -X POST localhost:8080/api/v1/wallets -H "Content-Type: application/json" \
  -d '{"walletId":1,"balance":10000,"userId":101}'
curl -X POST localhost:8080/api/v1/wallets -H "Content-Type: application/json" \
  -d '{"walletId":2,"balance":5000,"userId":102}'

# 3. Create & process payment
curl -X POST localhost:8080/api/v1/payments -H "Content-Type: application/json" \
  -d '{"fromWalletId":1,"toWalletId":2,"amount":500,"idempotencyKey":"demo-001","paymentType":"P2P"}'
curl -X POST localhost:8080/api/v1/payments/1/process

# 4. Prove idempotency (same key = same payment)
curl -X POST localhost:8080/api/v1/payments -H "Content-Type: application/json" \
  -d '{"fromWalletId":1,"toWalletId":2,"amount":500,"idempotencyKey":"demo-001","paymentType":"P2P"}'

# 5. Verify ledger
curl localhost:8080/api/v1/wallets/1/balance

# 6. Reconciliation
curl localhost:8080/api/v1/reconciliation
```
