# Part 4: Three-Layer Idempotency & Distributed Locking

> This is the reliability layer. Interviewers LOVE asking: "What happens if the client retries? What happens if two servers process the same payment?"

---

## What Is Idempotency?

**Idempotency** means: doing the same operation multiple times produces the same result as doing it once.

**Why it matters for payments:**
```
Client sends:  POST /payments { amount: 500, idempotencyKey: "txn-001" }
Network timeout — client doesn't know if it worked
Client retries: POST /payments { amount: 500, idempotencyKey: "txn-001" }
```

Without idempotency: Two payments created, customer charged ₹1000 instead of ₹500 💀
With idempotency: Second request returns the SAME payment — no duplicate charge ✅

---

## The Three Layers

```
REQUEST ARRIVES
     │
     ▼
┌─────────────────────────────────────────────────┐
│  LAYER 1: Redis (Fast Path)                      │
│  SET idempotency:{key} NX EX 300                 │
│                                                   │
│  ✅ Key doesn't exist → NEW request, proceed      │
│  ❌ Key exists → DUPLICATE, return existing       │
│                                                   │
│  Speed: <1ms | Durability: temporary (5-min TTL) │
│  If Redis is DOWN: skip, fall through to Layer 2 │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│  LAYER 2: PostgreSQL UNIQUE constraint           │
│  Column: idempotency_key (UNIQUE)                │
│                                                   │
│  ✅ INSERT succeeds → new payment                 │
│  ❌ DataIntegrityViolationException → duplicate   │
│                                                   │
│  Speed: 5-20ms | Durability: PERMANENT           │
│  ALWAYS works — this is the ultimate safety net  │
└─────────────────────┬───────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────┐
│  LAYER 3: Application-level check                │
│  paymentRepo.findByIdempotencyKey(key)           │
│                                                   │
│  Check the DB before inserting.                   │
│  Defensive coding — catches edge cases.           │
└─────────────────────────────────────────────────┘
```

### Why Three Layers?

| Layer | Strength | Weakness |
|:------|:---------|:---------|
| **Redis** | Sub-millisecond speed | Temporary (TTL expires), can go down |
| **DB UNIQUE** | Permanent, ACID-guaranteed | Slower (disk I/O), exception-based |
| **App check** | Defensive, handles edge cases | Slightly redundant, but safe |

Together, they're bulletproof. Each layer covers the weaknesses of the others.

---

## Layer 1: Redis Idempotency (RedisIdempotencyService)

```java
// Key format: "idempotency:txn-001"
// Value: paymentId (e.g., "42")
// TTL: 300 seconds (configurable)

public boolean claimKey(String idempotencyKey, Long paymentId) {
    String redisKey = KEY_PREFIX + idempotencyKey;  // "idempotency:txn-001"
    String value = String.valueOf(paymentId);        // "42"
    
    // SET NX EX — single atomic Redis command
    // NX = only set if Not eXists
    // EX = expire after configured seconds
    Boolean claimed = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, value, ttl);
    
    return Boolean.TRUE.equals(claimed);
}
```

**Why SET NX is perfect:** It's atomic. Two servers calling it at the exact same time — only ONE will succeed. No race condition possible.

**Why TTL?** Keys expire after 5 minutes (dev) or 24 hours (prod, like Stripe). After expiry, the idempotency key can be reused. The PostgreSQL UNIQUE constraint is the permanent record.

---

## Layer 2: PostgreSQL UNIQUE Constraint

```sql
-- From V2 migration:
ALTER TABLE payments ADD CONSTRAINT uk_payments_idempotency_key 
    UNIQUE (idempotency_key);
```

If two concurrent INSERT statements try the same `idempotency_key`, PostgreSQL rejects the second one with `DataIntegrityViolationException`:

```java
try {
    paymentRepo.save(payment);
} catch (DataIntegrityViolationException e) {
    // UNIQUE constraint rejected this — it's a duplicate
    Payment alreadySaved = paymentRepo.findByIdempotencyKey(idempotencyKey);
    return alreadySaved;  // Return existing, don't create new
}
```

---

## The Full Flow in PaymentService.createPayment()

```
1. Redis check: "Does idempotency:txn-001 exist?"
   → YES: Look up existing payment in DB, return it
   → NO: Continue
   → Redis DOWN: Log warning, continue to DB fallback

2. DB check: "SELECT * FROM payments WHERE idempotency_key = 'txn-001'"
   → Found: Return existing payment
   → Not found: Continue

3. INSERT payment into DB
   → Success: Claim key in Redis (SET NX), return new payment
   → UNIQUE violation: Another thread beat us
     → Look up existing, return it

4. Publish PAYMENT_CREATED event (outbox)
```

---

## Graceful Degradation: Redis Down

Every Redis call is wrapped in try-catch:

```java
try {
    if (idempotencyService.exists(idempotencyKey)) {
        // Redis says duplicate...
    }
} catch (Exception e) {
    // Redis is DOWN — that's okay!
    log.warn("[Payment] Redis unavailable — falling back to DB-only");
    // Continue to Layer 2 (PostgreSQL) — system still works
}
```

**When Redis is down:**
- Layer 1 (Redis) is skipped
- Layer 2 (DB UNIQUE) catches duplicates
- Layer 3 (App check) adds another safety layer
- System works correctly, just slightly slower (5ms instead of <1ms)

---

## Distributed Locking (RedisDistributedLockService)

### The Problem

```
Server A                          Server B
─────────                         ─────────
processPayment(42)                processPayment(42)
Read wallet 1: balance=10000      Read wallet 1: balance=10000
Debit 500 → balance=9500          Debit 500 → balance=9500
Save → wallet 1 = 9500            Save → wallet 1 = 9500

Result: Wallet 1 lost ₹500, but Wallet 2 received ₹1000! 💀
```

### The Solution: Wallet-Level Lock

Before processing, acquire a lock on the sender's wallet:

```java
// Acquire: SET lock:wallet:1 {uuid} NX EX 30
LockHandle walletLock = lockService.tryLock(payment.getFromWalletId());
if (walletLock == null) {
    throw new IllegalStateException("Wallet is locked by another server");
}

try {
    // ... process payment (only one server can reach here) ...
} finally {
    // Release: Lua script checks UUID, then DEL
    lockService.releaseLock(walletLock);
}
```

### Why UUID?

Each lock acquisition generates a unique UUID:

```java
String lockValue = UUID.randomUUID().toString();
```

This prevents Server A from accidentally releasing Server B's lock. The release uses a Lua script:

```lua
-- Atomic: check owner, then delete
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

**Why a Lua script?** Without it, there's a race condition:
1. Server A checks: "Is this my lock?" → YES
2. Server A's lock expires (TTL) between the check and the delete
3. Server B acquires the lock
4. Server A's DELETE removes Server B's lock!

The Lua script runs GET + DEL atomically in Redis — impossible to race.

### Lock TTL: Auto-Release

```
TTL = 30 seconds
```

If a server crashes while holding a lock, the lock auto-releases after 30 seconds. No deadlocks possible.

---

## Processing Marker (RedisProcessingMarkerService)

### Different From Idempotency and Locking

| Service | Prevents | Key Format | Scope |
|:--------|:---------|:-----------|:------|
| **Idempotency** | Duplicate payment CREATION | `idempotency:{key}` | Per idempotency key |
| **Distributed Lock** | Two payments from SAME WALLET concurrently | `lock:wallet:{walletId}` | Per wallet |
| **Processing Marker** | Same PAYMENT processed by two threads | `processing:payment:{id}` | Per payment |

### The Scenario It Prevents

```
Thread 1 (RetryScheduler): processPayment(42)
Thread 2 (API call):       processPayment(42)

Without marker: Both threads process payment 42 → DOUBLE CHARGE
With marker: Thread 2 sees marker → rejects immediately
```

### How It Works

```java
// Before processing:
boolean markerSet = processingMarkerService.markProcessing(paymentId);
// SET processing:payment:42 NX EX 60
// Returns true (proceed) or false (someone else is processing)

try {
    // ... process payment ...
} finally {
    // After processing:
    if (markerSet) {
        processingMarkerService.clearMarker(paymentId);
        // DEL processing:payment:42
    }
}
```

---

## Summary: Three Redis Services, Three Different Problems

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  RedisIdempotencyService       ── "Is this a duplicate REQUEST?" │
│  Key: idempotency:{key}                                          │
│  Scope: Per client-provided idempotency key                      │
│  TTL: 5 min (dev) / 24h (prod)                                  │
│                                                                  │
│  RedisDistributedLockService   ── "Is this WALLET busy?"         │
│  Key: lock:wallet:{walletId}                                     │
│  Scope: Per wallet (sender)                                      │
│  TTL: 30 seconds                                                 │
│                                                                  │
│  RedisProcessingMarkerService  ── "Is this PAYMENT in progress?" │
│  Key: processing:payment:{id}                                    │
│  Scope: Per payment                                               │
│  TTL: 60 seconds                                                 │
│                                                                  │
│  ALL use the same Redis command: SET {key} {value} NX EX {ttl}  │
│  ALL have graceful degradation if Redis goes down                │
└──────────────────────────────────────────────────────────────────┘
```

---

## Interview Talking Points

**Q: "How do you prevent duplicate charges?"**

> "We use three-layer idempotency. Layer 1: Redis SET NX provides sub-millisecond duplicate detection. Layer 2: PostgreSQL UNIQUE constraint on idempotency_key is the permanent safety net. Layer 3: An application-level findByIdempotencyKey check adds defensive coding. Each layer covers the weaknesses of the others — Redis can go down, but PostgreSQL always catches it."

**Q: "What happens if two servers process the same payment?"**

> "Three mechanisms prevent this. First, a Redis processing marker ensures only one thread can process a specific payment. Second, a Redis distributed lock on the sender's wallet prevents concurrent debits. Third, the wallet lock uses a UUID + Lua script for safe release, and a 30-second TTL prevents deadlocks if a server crashes."

**Q: "How do you handle Redis being down?"**

> "Every Redis call is wrapped in try-catch. If Redis is unavailable, the system logs a warning and falls back to database-only safety nets — the UNIQUE constraint still prevents duplicates, and PostgreSQL's @Transactional isolation provides some concurrency protection. It's slower but correct. Redis is a cache layer, PostgreSQL is the source of truth."
