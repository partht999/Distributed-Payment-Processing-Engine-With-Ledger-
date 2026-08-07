# Part 6: Interview Questions & Answers — Complete Preparation

> This is your interview cheat sheet. Covers System Design, Backend Engineering, and Behavioral questions specific to this project.

---

## 🏛️ System Design Questions

### Q1: "Tell me about this project. What does it do?"

> "I built a distributed payment processing engine — the kind of backend system that powers Stripe or Razorpay. It handles the full payment lifecycle: creating payments, transferring money between wallets, two-phase merchant authorization and capture, refunds, and reversals.
>
> What makes it production-grade is the reliability layer: three-layer idempotency to prevent duplicate charges, Redis distributed locking to prevent double-spend, an immutable double-entry ledger for financial correctness, a transactional outbox pattern for zero event loss, and HMAC-signed webhooks for merchant notifications.
>
> Tech stack is Java 17, Spring Boot 4.0, PostgreSQL, Redis, Kafka in KRaft mode, with Docker Compose orchestrating everything plus Prometheus/Grafana for monitoring."

---

### Q2: "Walk me through the architecture."

> "It's a modular monolith with four layers:
>
> 1. **Controller Layer** — REST API endpoints (PaymentController, WalletController, etc.)
> 2. **Service Layer** — Business logic (PaymentService orchestrates everything, WalletService handles money movement)
> 3. **Safety Layer** — Three Redis services (idempotency, distributed locks, processing markers) plus database constraints
> 4. **Event Layer** — Transactional outbox saves events atomically, a poller publishes to Kafka, a consumer dispatches webhooks
>
> PostgreSQL is the source of truth. Redis is a cache and coordination layer that can go down without losing data. Kafka decouples payment processing from notifications."

---

### Q3: "Why a modular monolith instead of microservices?"

> "For a financial system, correctness trumps everything. With a monolith, I get ACID transactions that span the payment, ledger, and outbox event in a single database commit. With microservices, I'd need distributed transactions (2PC or Saga pattern), which adds enormous complexity.
>
> The system IS designed for future decomposition though — the service interfaces are clean, the outbox pattern already decouples the event pipeline, and each service has its own repository. When the team grows to 10+ engineers, you can extract WalletService into its own microservice."

---

### Q4: "How do you prevent duplicate charges?"

> "Three-layer idempotency:
>
> **Layer 1 — Redis:** `SET idempotency:{key} NX EX 300` — sub-millisecond duplicate detection. The `NX` flag means 'set only if not exists', so it's atomic.
>
> **Layer 2 — PostgreSQL:** A `UNIQUE` constraint on the `idempotency_key` column. Even if Redis is down, the database catches duplicates via `DataIntegrityViolationException`.
>
> **Layer 3 — Application:** A `findByIdempotencyKey()` check before INSERT. Defensive coding for edge cases like Redis having the key but the DB not having the payment yet.
>
> Each layer covers the others' weaknesses. Redis is fast but temporary. PostgreSQL is permanent but slower. The app check handles edge cases."

---

### Q5: "What happens if two servers try to process the same payment?"

> "Three mechanisms prevent this:
>
> 1. **Processing Marker** — `SET processing:payment:{id} NX EX 60` — ensures only one thread/server can claim a specific payment for processing.
>
> 2. **Distributed Lock** — `SET lock:wallet:{walletId} NX EX 30` — the sender's wallet is locked, preventing concurrent debits from the same wallet.
>
> 3. **Safe Release** — Lock release uses a Lua script that atomically checks the UUID before deleting. This prevents Server A from accidentally releasing Server B's lock."

---

### Q6: "Explain the transactional outbox pattern."

> "The problem: you need to update the database AND publish to Kafka, but they're different systems — you can't put them in the same ACID transaction. If the DB write succeeds but Kafka write fails, you lose the event.
>
> Solution: Instead of writing directly to Kafka, I write the event to an `outbox_events` table in the SAME database transaction as the payment. Both either commit or both roll back — guaranteed.
>
> A poller runs every 5 seconds, queries for PENDING outbox events, publishes them to Kafka, and marks them as PUBLISHED. If the poller crashes after publishing but before marking, the event stays PENDING and gets published again — at-least-once delivery. The consumer's idempotency table prevents double-processing."

---

### Q7: "How does the immutable ledger work?"

> "Every financial event creates two entries: a DEBIT on one wallet and a CREDIT on another. Entries are never updated or deleted. The wallet's balance column is just a cache — the true balance is `SUM(CREDITs) - SUM(DEBITs)` from the ledger.
>
> Refunds don't delete entries — they create new compensating entries. This means I have a complete audit trail and can reconstruct any wallet's balance at any point in time.
>
> I also built a reconciliation engine with three checks: total DEBITs equal total CREDITs, each wallet's cached balance matches its ledger-derived balance, and no orphan entries exist."

---

### Q8: "How do you handle Kafka/Redis being down?"

> "Graceful degradation:
>
> **Redis down:** Every Redis call is wrapped in try-catch. The system falls back to database-only mode. Idempotency relies on the UNIQUE constraint, locking relies on PostgreSQL's transaction isolation, and the system logs WARN messages.
>
> **Kafka down:** Events stay safely in the outbox_events table (status = PENDING). The poller retries on the next cycle. When Kafka comes back, all pending events are published. Zero event loss.
>
> **Webhook endpoint down:** Delivery failure is logged. The consumer still commits the Kafka offset (we don't block the partition for a webhook failure)."

---

### Q9: "Explain the webhook signing mechanism."

> "When we POST a payment event to a merchant's webhook URL, we sign the JSON payload with HMAC-SHA256 using a shared secret that was established when the merchant registered.
>
> The signature goes in the `X-Signature` header. The merchant computes the same HMAC on their end using their stored secret and compares it to the header. If they match, the webhook is authentic and hasn't been tampered with.
>
> This is the same pattern Stripe and Razorpay use. It prevents attackers from sending fake webhooks."

---

### Q10: "What's the difference between AUTHORIZED and CAPTURED?"

> "Authorization and capture is a two-phase flow used in e-commerce:
>
> **AUTHORIZED** means funds are reserved but not yet transferred. Think of it as a hold on the customer's card. This happens when a customer clicks 'Buy' but the merchant hasn't shipped yet.
>
> **CAPTURED** means funds are actually transferred. This happens when the merchant confirms the order (e.g., when the item ships).
>
> If the merchant wants to cancel, they call `reverse` — the hold is released and no money ever moved. This is different from a refund, where money was already transferred and needs to be sent back."

---

## ⚙️ Backend Engineering Questions

### Q11: "Why PostgreSQL over MySQL?"

> "PostgreSQL has better ACID guarantees, better handling of concurrent transactions with MVCC, richer constraint types (partial unique indexes for merchant order IDs), and is used by Stripe in production. For a financial system, these differences matter."

---

### Q12: "Why Redis for locking instead of database locks?"

> "Two reasons: (1) Speed — Redis SET NX is sub-millisecond, while a database SELECT FOR UPDATE involves disk I/O. (2) Distribution — Redis locks work across multiple application servers, while database row locks only work within a single database connection. In a multi-server deployment, Redis locks are essential for coordination."

---

### Q13: "How do Flyway migrations work?"

> "Flyway is a version-controlled migration tool. SQL files are named `V1__description.sql`, `V2__description.sql`, etc. On application startup, Flyway checks the `flyway_schema_history` table to see which migrations have been applied, and runs only the new ones.
>
> We have 6 migrations: initial schema, unique constraints, partial indexes for merchant orders, outbox events table, webhook configs table, and processed events table. Each migration is applied exactly once, in order."

---

### Q14: "Why KRaft mode for Kafka?"

> "KRaft mode eliminates the ZooKeeper dependency. In traditional Kafka, you need a separate ZooKeeper cluster for metadata management. KRaft mode makes Kafka self-contained — the broker manages its own metadata. This simplifies deployment and reduces operational overhead."

---

### Q15: "How does Redis caching work in WalletService?"

> "We use Spring's `@Cacheable` annotation with Redis as the backing store. When `getWallet(id)` is called, Spring checks Redis first (key: `wallets::{id}`). If found (cache hit), it returns immediately — sub-millisecond. If not found (cache miss), it queries PostgreSQL, stores the result in Redis with a 10-minute TTL, and returns it.
>
> Write operations (`transfer`, `deposit`, `withdraw`) use `@CacheEvict` to invalidate the cached entry. This ensures the next read gets fresh data from the database."

---

## 🧠 Design Decision Questions

### Q16: "Why integer amounts instead of BigDecimal?"

> "Floating point has precision issues: `0.1 + 0.2 = 0.30000000000000004`. For money, we store amounts in the smallest currency unit — paise for INR, cents for USD. So ₹500 is stored as `500` (long integer). Integer arithmetic is always precise. Display formatting happens in the frontend."

---

### Q17: "Why a poller instead of CDC for the outbox?"

> "A poller is simpler to implement, debug, and maintain. CDC (Change Data Capture, like Debezium) is powerful but adds another infrastructure component. For a 5-second delivery delay, a poller is perfectly adequate. In a high-scale production system processing millions of events, CDC would be the next evolution."

---

### Q18: "Why manual JSON serialization in OutboxEventPublisher?"

> "To avoid introducing Jackson ObjectMapper as a dependency into the event publisher. The manual StringBuilder approach has zero dependencies and is explicit about what fields are serialized. In production, you'd use Jackson or Gson, but for clarity in a learning project, manual serialization makes the process transparent."

---

## 💬 Behavioral / "Why" Questions

### Q19: "What was the hardest part of building this?"

> "Getting the three layers of idempotency right. The tricky part is edge cases: what if Redis has the key but the database doesn't? What if the INSERT succeeds but Redis is down for the SET? I had to think through every possible failure scenario and make sure each layer correctly falls back to the next."

---

### Q20: "What would you do differently if starting over?"

> "I'd use Jackson ObjectMapper for JSON serialization instead of manual StringBuilder. I'd also add proper API rate limiting and authentication (JWT). And I'd consider using Debezium for CDC instead of a poller if the scale required sub-second event delivery."

---

### Q21: "How would you scale this to handle 10x traffic?"

> "Three things:
> 1. **Horizontal scaling** — Run multiple app instances behind a load balancer. The Redis locks and Kafka consumer groups already support this.
> 2. **Read replicas** — Add PostgreSQL read replicas for balance queries. Write operations go to the primary.
> 3. **Kafka partitioning** — Add more partitions and consumers for parallel event processing. The key-based partitioning already ensures ordering per payment."

---

### Q22: "How is this different from other payment projects on GitHub?"

> "Most payment projects are CRUD apps with a payments table. This one implements production-level reliability patterns: three-layer idempotency, distributed locking with Lua scripts, an immutable double-entry ledger, a transactional outbox pattern, HMAC-signed webhooks, dead letter queue, reconciliation engine, and graceful degradation. These are the patterns that companies like Stripe and Razorpay actually use."

---

## 📊 Quick Reference: All API Endpoints

| Method | Endpoint | Purpose |
|:------:|:---------|:--------|
| POST | `/api/v1/wallets` | Create wallet |
| GET | `/api/v1/wallets/{id}` | Get wallet |
| GET | `/api/v1/wallets/{id}/balance` | Balance (cached + ledger-derived) |
| POST | `/api/v1/wallets/{id}/deposit` | Deposit funds |
| POST | `/api/v1/wallets/{id}/withdraw` | Withdraw funds |
| GET | `/api/v1/wallets` | List all wallets |
| POST | `/api/v1/payments` | Create payment |
| GET | `/api/v1/payments/{id}` | Get payment |
| GET | `/api/v1/payments` | List all payments |
| POST | `/api/v1/payments/{id}/process` | Process payment |
| POST | `/api/v1/payments/{id}/authorize` | Authorize (merchant) |
| POST | `/api/v1/payments/{id}/capture` | Capture (merchant) |
| POST | `/api/v1/payments/{id}/refund` | Refund |
| POST | `/api/v1/payments/{id}/reverse` | Reverse authorization |
| GET | `/api/v1/reconciliation` | Run ledger reconciliation |
| GET | `/api/v1/health` | Health check |
