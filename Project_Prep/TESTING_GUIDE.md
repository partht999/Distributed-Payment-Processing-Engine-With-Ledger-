<div align="center">

# 🧪 Complete Testing & Demo Guide

### Distributed Payment Processing Engine with Immutable Ledger

**Everything you need to run, test, and demo this project**

---

</div>

## 📋 Table of Contents

1. [Prerequisites](#-prerequisites)
2. [Step 1 — Start Infrastructure](#-step-1--start-infrastructure)
3. [Step 2 — Run the Application](#-step-2--run-the-application)
4. [Step 3 — Verify Health](#-step-3--verify-health)
5. [Step 4 — Test Wallet Operations](#-step-4--test-wallet-operations)
6. [Step 5 — Test P2P Payment Flow](#-step-5--test-p2p-payment-flow)
7. [Step 6 — Test Idempotency](#-step-6--test-idempotency)
8. [Step 7 — Verify Immutable Ledger](#-step-7--verify-immutable-ledger)
9. [Step 8 — Test Refund](#-step-8--test-refund)
10. [Step 9 — Test Merchant Auth+Capture Flow](#-step-9--test-merchant-authcapture-flow)
11. [Step 10 — Test Reversal](#-step-10--test-reversal)
12. [Step 11 — Test State Machine Guards](#-step-11--test-state-machine-guards)
13. [Step 12 — Test Deposit & Withdraw](#-step-12--test-deposit--withdraw)
14. [Step 13 — Run Reconciliation](#-step-13--run-reconciliation)
15. [Step 14 — Swagger/OpenAPI Docs](#-step-14--swaggeropenapi-docs)
16. [Step 15 — Run Automated Tests](#-step-15--run-automated-tests)
17. [Step 16 — Run E2E Demo Script](#-step-16--run-e2e-demo-script)
18. [Step 17 — Load Testing with k6](#-step-17--load-testing-with-k6)
19. [Step 18 — Monitoring (Prometheus + Grafana)](#-step-18--monitoring-prometheus--grafana)
20. [Step 19 — Docker Full Stack](#-step-19--docker-full-stack)
21. [Cleanup](#-cleanup)
22. [Troubleshooting](#-troubleshooting)

---

## 🔧 Prerequisites

| Tool | Version | Check Command |
|:-----|:--------|:-------------|
| **Java JDK** | 17+ | `java -version` |
| **Docker Desktop** | Latest | `docker --version` |
| **Docker Compose** | V2 (included with Docker Desktop) | `docker compose version` |
| **Git** | Any | `git --version` |
| **curl** | Any (or PowerShell) | `curl --version` |
| **k6** (optional) | Latest | `k6 version` (for load testing only) |

---

## 🐳 Step 1 — Start Infrastructure

Open a terminal in the `payment_engine/` directory:

```bash
cd payment_engine
```

**Start PostgreSQL, Redis, and Kafka:**

```bash
docker compose up -d postgres redis kafka
```

**Wait for all services to be healthy (~30 seconds):**

```bash
docker compose ps
```

You should see:

| Service | Container | Status | Port |
|:--------|:----------|:------:|:-----|
| PostgreSQL 15 | `payment_engine_db` | healthy | `localhost:5433` |
| Redis 7 | `payment_engine_redis` | healthy | `localhost:6379` |
| Kafka 3.7 (KRaft) | `payment_engine_kafka` | healthy | `localhost:9092` |

> ⚠️ **Wait until all 3 show `(healthy)` before proceeding.** Kafka can take 30+ seconds.

---

## 🚀 Step 2 — Run the Application

**Option A: Run from source (recommended for development)**

```bash
./mvnw spring-boot:run
```

Wait until you see:
```
Started PaymentEngineApplication in X.XXX seconds
```

**Option B: Run the full Docker stack (everything containerized)**

```bash
docker compose up --build -d
```

This builds the app image and starts ALL 6 services (app + infrastructure + monitoring).

The app will be available at: **http://localhost:8080**

---

## 🏥 Step 3 — Verify Health

```bash
curl http://localhost:8080/api/v1/health
```

**Expected response:**
```json
{
  "status": "UP",
  "service": "Payment Engine",
  "timestamp": "2026-08-07T18:34:20.592Z",
  "redis": "UP"
}
```

✅ If you see `"status": "UP"` — the app is ready.

---

## 👛 Step 4 — Test Wallet Operations

### 4.1 Create Wallet A (sender, ₹10,000 balance)

```bash
curl -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"walletId": 1, "balance": 10000, "userId": 101, "phoneNumber": 9876543210}'
```

**Expected:**
```json
{
  "walletId": 1,
  "balance": 10000,
  "userId": 101,
  "status": "ACTIVE"
}
```

### 4.2 Create Wallet B (receiver, ₹5,000 balance)

```bash
curl -X POST http://localhost:8080/api/v1/wallets \
  -H "Content-Type: application/json" \
  -d '{"walletId": 2, "balance": 5000, "userId": 102, "phoneNumber": 9876543211}'
```

### 4.3 Get Wallet Details

```bash
curl http://localhost:8080/api/v1/wallets/1
```

### 4.4 List All Wallets

```bash
curl http://localhost:8080/api/v1/wallets
```

---

## 💳 Step 5 — Test P2P Payment Flow

### 5.1 Create a P2P Payment (₹500 from Wallet 1 → Wallet 2)

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "fromWalletId": 1,
    "toWalletId": 2,
    "amount": 500,
    "idempotencyKey": "txn-001",
    "paymentType": "P2P"
  }'
```

**Expected — Status = `CREATED`:**
```json
{
  "paymentId": 1,
  "fromWalletId": 1,
  "toWalletId": 2,
  "amount": 500,
  "status": "CREATED",
  "idempotencyKey": "txn-001",
  "paymentType": "P2P",
  "retryCount": 0
}
```

> 📝 **Note the `paymentId`** — you'll use it in subsequent steps. Replace `{paymentId}` below with the actual ID.

### 5.2 Process the Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/process
```

**Expected — Status = `CAPTURED`:**
```json
{
  "paymentId": 1,
  "status": "CAPTURED",
  ...
}
```

**What happened under the hood:**
1. Redis processing marker set (dedup guard)
2. Redis distributed lock acquired on Wallet 1
3. Ledger-derived balance verified (sufficient funds)
4. DEBIT entry appended to ledger (Wallet 1: -500)
5. CREDIT entry appended to ledger (Wallet 2: +500)
6. Transaction record created
7. Wallet balances updated
8. Payment status → CAPTURED
9. Outbox event saved (same transaction)
10. Lock released, marker cleared
11. Outbox poller → Kafka → Consumer → Webhook

---

## 🔒 Step 6 — Test Idempotency

**Send the EXACT same request with the same idempotency key:**

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "fromWalletId": 1,
    "toWalletId": 2,
    "amount": 500,
    "idempotencyKey": "txn-001",
    "paymentType": "P2P"
  }'
```

**Expected — Returns the SAME payment (no duplicate!):**
```json
{
  "paymentId": 1,
  "status": "CAPTURED",
  "idempotencyKey": "txn-001"
}
```

✅ **Same `paymentId`, no new payment created.** This is the three-layer idempotency at work:
1. **Redis** — `SET NX` fast-path check
2. **PostgreSQL** — `UNIQUE` constraint on `idempotency_key`
3. **Application** — `findByIdempotencyKey()` DB lookup

---

## 📒 Step 7 — Verify Immutable Ledger

### 7.1 Check Wallet 1 Balance (should be ₹9,500)

```bash
curl http://localhost:8080/api/v1/wallets/1/balance
```

**Expected:**
```json
{
  "walletId": 1,
  "walletBalance": 9500,
  "ledgerDerivedBalance": 9500
}
```

✅ **`walletBalance` matches `ledgerDerivedBalance`** — the cached balance and the ledger-computed balance are in sync.

### 7.2 Check Wallet 2 Balance (should be ₹5,500)

```bash
curl http://localhost:8080/api/v1/wallets/2/balance
```

**Expected:**
```json
{
  "walletId": 2,
  "walletBalance": 5500,
  "ledgerDerivedBalance": 5500
}
```

---

## ↩️ Step 8 — Test Refund

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/refund
```

**Expected — Status = `REFUNDED`:**
```json
{
  "paymentId": 1,
  "status": "REFUNDED"
}
```

**Verify balances are restored:**

```bash
curl http://localhost:8080/api/v1/wallets/1/balance
# Expected: walletBalance = 10000, ledgerDerivedBalance = 10000

curl http://localhost:8080/api/v1/wallets/2/balance
# Expected: walletBalance = 5000, ledgerDerivedBalance = 5000
```

✅ **Refund created compensating ledger entries** (DEBIT on Wallet 2, CREDIT on Wallet 1). No entries were deleted — the ledger is immutable.

---

## 🏪 Step 9 — Test Merchant Auth+Capture Flow

This is the two-phase flow used by Stripe/Razorpay for merchant payments.

### 9.1 Create a Merchant Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "fromWalletId": 1,
    "toWalletId": 2,
    "amount": 2000,
    "idempotencyKey": "merchant-001",
    "paymentType": "MERCHANT",
    "merchantOrderId": "ORDER-XYZ-789"
  }'
```

**Expected:** `status: "CREATED"` — Note the `paymentId`.

### 9.2 Authorize the Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/authorize
```

**Expected:** `status: "AUTHORIZED"` — Funds are reserved but not yet transferred.

### 9.3 Capture the Payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/capture
```

**Expected:** `status: "CAPTURED"` — Funds are now transferred.

**State Machine Transitions:**
```
CREATED → AUTHORIZED → CAPTURED
```

---

## 🔄 Step 10 — Test Reversal

Create another merchant payment and authorize it, then reverse it (instead of capturing):

```bash
# Create
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "fromWalletId": 1,
    "toWalletId": 2,
    "amount": 1000,
    "idempotencyKey": "merchant-002",
    "paymentType": "MERCHANT",
    "merchantOrderId": "ORDER-ABC-456"
  }'

# Authorize (use the paymentId from above)
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/authorize

# Reverse (cancel the authorization)
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/reverse
```

**Expected:** `status: "REVERSED"` — No funds were ever moved.

---

## 🛡️ Step 11 — Test State Machine Guards

The state machine prevents invalid transitions. Try these — they should all **fail**:

### 11.1 Cannot process a REFUNDED payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{refundedPaymentId}/process
```
**Expected:** `400` or `500` error — "Payment was refunded. Cannot re-process."

### 11.2 Cannot capture without authorizing first

```bash
# Create a new payment
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"fromWalletId": 1, "toWalletId": 2, "amount": 100, "idempotencyKey": "guard-test-001", "paymentType": "MERCHANT"}'

# Try to capture directly (skip authorize)
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/capture
```
**Expected:** Error — "Must authorize first"

### 11.3 Cannot reverse a non-authorized payment

```bash
curl -X POST http://localhost:8080/api/v1/payments/{paymentId}/reverse
```
**Expected:** Error — "Only authorized payments can be reversed"

---

## 💰 Step 12 — Test Deposit & Withdraw

### 12.1 Deposit ₹500 to Wallet 1

```bash
curl -X POST http://localhost:8080/api/v1/wallets/1/deposit \
  -H "Content-Type: application/json" \
  -d '{"amount": 500}'
```

**Expected:**
```json
{
  "walletId": 1,
  "message": "Deposit successful",
  "amount": 500,
  "newBalance": 10500
}
```

### 12.2 Withdraw ₹200 from Wallet 1

```bash
curl -X POST http://localhost:8080/api/v1/wallets/1/withdraw \
  -H "Content-Type: application/json" \
  -d '{"amount": 200}'
```

**Expected:**
```json
{
  "walletId": 1,
  "message": "Withdrawal successful",
  "amount": 200,
  "newBalance": 10300
}
```

### 12.3 Test Insufficient Balance

```bash
curl -X POST http://localhost:8080/api/v1/wallets/1/withdraw \
  -H "Content-Type: application/json" \
  -d '{"amount": 999999}'
```

**Expected:** Error — "Insufficient balance"

---

## 🔍 Step 13 — Run Reconciliation

The reconciliation engine proves the ledger is financially correct.

```bash
curl http://localhost:8080/api/v1/reconciliation
```

**Expected (healthy system):**
```json
{
  "timestamp": "2026-08-07T18:36:09Z",
  "doubleEntryBalance": {
    "passed": true,
    "totalDebits": 3000,
    "totalCredits": 3000,
    "systemCredits": 15000,
    "netDifference": 0
  },
  "walletBalanceVerification": {
    "passed": true,
    "walletsVerified": 2,
    "walletsWithMismatch": 0
  },
  "orphanDetection": {
    "passed": true,
    "orphanCount": 0
  },
  "overallStatus": "HEALTHY",
  "totalLedgerEntries": 8,
  "totalWallets": 2
}
```

**Three checks performed:**
1. **Double-Entry Balance** — Total DEBITs = Total CREDITs (money not created/destroyed)
2. **Wallet Balance vs Ledger** — Cached balance matches ledger-derived balance
3. **Orphan Detection** — No ledger entries reference non-existent wallets

✅ `"overallStatus": "HEALTHY"` = your ledger is financially correct.

---

## 📖 Step 14 — Swagger/OpenAPI Docs

Open in your browser:

```
http://localhost:8080/swagger-ui/index.html
```

This gives you an interactive API explorer where you can test all endpoints directly.

---

## 🧪 Step 15 — Run Automated Tests

> ⚠️ **Docker must be running** (PostgreSQL + Redis + Kafka) before running tests.

**Run all tests:**

```bash
./mvnw test
```

**Run a specific test class:**

```bash
./mvnw test -Dtest=PaymentIntegrationTest
./mvnw test -Dtest=RedisIntegrationTest
./mvnw test -Dtest=DistributedLockTest
./mvnw test -Dtest=IdempotencyTtlTest
./mvnw test -Dtest=ProcessingMarkerTest
```

**Test classes and what they cover:**

| Test Class | What It Tests | Methods |
|:-----------|:-------------|:-------:|
| `PaymentIntegrationTest` | Full payment lifecycle E2E | 12 |
| `RedisIntegrationTest` | Redis idempotency, caching, failures | 10 |
| `DistributedLockTest` | Concurrent payment protection with locks | 8 |
| `IdempotencyTtlTest` | TTL expiration and key reuse | 6 |
| `ProcessingMarkerTest` | Processing dedup guard | 5 |
| `PaymentEngineApplicationTests` | Spring context loads | 1 |

---

## 🎯 Step 16 — Run E2E Demo Script

The automated E2E script tests the entire pipeline in one command:

```bash
# From payment_engine/ directory (Linux/Mac/WSL)
bash e2e-demo.sh
```

This runs 11 checks automatically:
1. Health check
2. Create 2 wallets
3. Create payment with idempotency key
4. Verify idempotency (same key = same payment)
5. Process payment (CREATED → CAPTURED)
6. Verify balances (ledger-derived)
7. Refund payment
8. Verify balances restored
9. State machine guard (cannot re-process refunded payment)

**Expected output:**
```
═══════════════════════════════════════════════════════
  💳 Distributed Payment Engine — Full E2E Test Suite
═══════════════════════════════════════════════════════

[1/8] Health Check
  ✅ PASS: Health endpoint returns 200
...
  PASSED: 11  |  FAILED: 0

🎉 ALL TESTS PASSED — System is working perfectly!
```

---

## 📊 Step 17 — Load Testing with k6

> Requires [k6](https://k6.io/docs/get-started/installation/) to be installed.

```bash
# From payment_engine/ directory
k6 run k6-load-test.js
```

The load test simulates:
- Wallet creation under load
- Payment creation with unique idempotency keys
- Payment processing
- Balance checks
- Concurrent requests to test locking

---

## 📈 Step 18 — Monitoring (Prometheus + Grafana)

### Start monitoring stack:

```bash
docker compose up -d prometheus grafana
```

### Access dashboards:

| Tool | URL | Credentials |
|:-----|:----|:-----------|
| **Prometheus** | http://localhost:9090 | N/A |
| **Grafana** | http://localhost:3000 | admin / admin |

### Prometheus metrics endpoint:

```bash
curl http://localhost:8080/actuator/prometheus
```

This exposes JVM, HTTP, Kafka, and Redis metrics for monitoring.

### Grafana Setup:

1. Open http://localhost:3000 → Login (admin/admin)
2. Go to **Data Sources** → **Add data source** → **Prometheus**
3. URL: `http://prometheus:9090` → Save & Test
4. Create dashboards for:
   - HTTP request rates and latencies
   - JVM memory and GC
   - Kafka consumer lag
   - Redis connection pool

---

## 🐳 Step 19 — Docker Full Stack

To run **everything** in Docker (app + all infrastructure + monitoring):

```bash
# Build and start all 6 services
docker compose up --build -d

# Check status
docker compose ps

# View app logs
docker compose logs -f payment-engine

# Stop everything
docker compose down

# Stop and remove all data (clean slate)
docker compose down -v
```

**Services and ports:**

| Service | Port | Purpose |
|:--------|:-----|:--------|
| Payment Engine | `8080` | Spring Boot REST API |
| PostgreSQL | `5433` | ACID database |
| Redis | `6379` | Cache + Locks + Idempotency |
| Kafka | `9092` | Event streaming |
| Prometheus | `9090` | Metrics collection |
| Grafana | `3000` | Dashboards |

---

## 🧹 Cleanup

### Stop infrastructure (keep data):
```bash
docker compose down
```

### Stop and delete all data (fresh start):
```bash
docker compose down -v
```

### Remove Docker images:
```bash
docker compose down -v --rmi all
```

---

## 🔧 Troubleshooting

### "Connection refused" on port 8080
- **Cause:** App isn't running yet
- **Fix:** Wait for `Started PaymentEngineApplication` in the logs, or run `./mvnw spring-boot:run`

### "Cannot connect to PostgreSQL"
- **Cause:** Docker container not healthy yet
- **Fix:** Run `docker compose ps` and wait for `(healthy)` status on postgres

### "Kafka not available" warnings in logs
- **Cause:** Kafka takes 30+ seconds to initialize in KRaft mode
- **Fix:** Wait. The outbox pattern queues events safely until Kafka is ready.

### Redis connection errors
- **Cause:** Redis container not started
- **Fix:** Run `docker compose up -d redis`. The app gracefully degrades without Redis.

### Tests failing
- **Cause:** Infrastructure not running
- **Fix:** `docker compose up -d postgres redis kafka` → wait for healthy → re-run tests

### "Port already in use"
- **Cause:** Another process on port 8080/5433/6379/9092
- **Fix:** `docker compose down` → check for orphan processes → restart

### Reconciliation shows DISCREPANCY_FOUND
- **Cause:** Wallets created in earlier sessions without ledger entries
- **Fix:** Clean restart with `docker compose down -v` → re-create wallets

---

<div align="center">

### 🎉 You're all set!

Run through Steps 1–13 for a complete demo.

**Happy testing!** ⚡

</div>
