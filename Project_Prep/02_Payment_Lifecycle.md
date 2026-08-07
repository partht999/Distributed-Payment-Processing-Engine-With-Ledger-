# Part 2: Payment Lifecycle & State Machine

> This is the CORE of the project. The interviewer will ask: "Walk me through what happens when a payment is created."

---

## The 8 Payment States

```
CREATED    → Payment record exists, no money moved yet
AUTHORIZED → (Merchant only) Funds are reserved/held
CAPTURED   → Money has been transferred — this is SUCCESS
RETRYING   → Processing failed, will retry automatically
FAILED     → All retries exhausted — permanently failed
EXPIRED    → Payment sat in CREATED too long (>2 minutes)
REFUNDED   → Captured payment reversed, money returned
REVERSED   → Authorized (not captured) payment cancelled
```

---

## State Transitions — What's Allowed

```
                    ┌─────────────────────────────────────────────┐
                    │                                             │
                    ▼                                             │
   [*] ───▶ CREATED ──────▶ CAPTURED ──────▶ REFUNDED ──▶ [*]  │
               │                                                  │
               ├──────────▶ AUTHORIZED ───▶ CAPTURED             │
               │                │                                 │
               │                └──────────▶ REVERSED ──▶ [*]    │
               │                                                  │
               ├──────────▶ RETRYING ──────▶ CAPTURED            │
               │                │                                 │
               │                └──────────▶ FAILED ────▶ [*]    │
               │                                                  │
               └──────────▶ EXPIRED ───────────────────▶ [*]     │
                                                                  │
                    ──────────────────────────────────────────────┘
```

### Valid Transitions Table

| From State | To State | How It Happens |
|:-----------|:---------|:---------------|
| CREATED | CAPTURED | `processPayment()` for P2P — money transferred directly |
| CREATED | AUTHORIZED | `processPayment()` for MERCHANT — funds held but not moved |
| CREATED | RETRYING | `processPayment()` fails (insufficient funds, etc.) |
| CREATED | EXPIRED | Payment not processed within 2 minutes |
| AUTHORIZED | CAPTURED | `capturePayment()` — merchant confirms, funds move |
| AUTHORIZED | REVERSED | `reversePayment()` — merchant cancels, nothing moved |
| RETRYING | CAPTURED | Retry succeeds |
| RETRYING | RETRYING | Retry fails, retryCount < maxRetries |
| RETRYING | FAILED | retryCount >= maxRetries (default: 3) |
| CAPTURED | REFUNDED | `refundPayment()` — reverse the transfer |

### BLOCKED Transitions (State Machine Guards)

These are prevented by code in PaymentService:

| Attempted | Error Message | Why |
|:----------|:-------------|:----|
| FAILED → anything | "Payment has permanently failed" | Terminal state |
| EXPIRED → anything | "Payment expired" | Terminal state |
| REFUNDED → process | "Payment was refunded" | Terminal state |
| REVERSED → process | "Payment was reversed" | Terminal state |
| CAPTURED → process | Returns same payment (idempotent) | Already done |
| CREATED → capture | "Must authorize first" | Skipped a step |
| CREATED → reverse | "Only authorized payments can be reversed" | Wrong state |

---

## Two Payment Flows — P2P vs Merchant

### Flow 1: P2P (Peer-to-Peer) — Simple Direct Transfer

```
CREATED ────▶ CAPTURED ────▶ (optional) REFUNDED

Step 1: POST /payments       → CREATED
Step 2: POST /payments/1/process → CAPTURED (money moves immediately)
Step 3: POST /payments/1/refund  → REFUNDED (money comes back)
```

**When to use:** User sends money to another user (like PhonePe, Google Pay).

### Flow 2: Merchant (Auth + Capture) — Two-Phase

```
CREATED ────▶ AUTHORIZED ────▶ CAPTURED
                   │
                   └──────────▶ REVERSED (if merchant cancels)

Step 1: POST /payments          → CREATED
Step 2: POST /payments/1/authorize → AUTHORIZED (funds reserved)
Step 3: POST /payments/1/capture   → CAPTURED (funds transferred)
   OR
Step 3: POST /payments/1/reverse   → REVERSED (funds released, nothing moved)
```

**When to use:** E-commerce checkout (like Amazon). Reserve first, capture when shipped.

**Why two phases?**
- Customer places order → AUTHORIZED (money is held)
- If item is in stock → CAPTURED (money transferred to merchant)
- If out of stock → REVERSED (money released back to customer)

---

## Retry Mechanism

When `processPayment()` fails (e.g., insufficient funds, wallet inactive):

```
Process attempt 1: FAIL → status = RETRYING (retryCount = 1)
Process attempt 2: FAIL → status = RETRYING (retryCount = 2)
Process attempt 3: FAIL → status = FAILED (retryCount >= maxRetries = 3)
```

**Who does the retrying?**

`PaymentRetryScheduler` — a Spring `@Scheduled` component that runs every 30 seconds:

```java
@Scheduled(fixedRate = 30000)
public void retryFailedPayments() {
    List<Payment> retryable = paymentRepo.findByStatus(PaymentStatus.RETRYING);
    for (Payment payment : retryable) {
        paymentService.processPayment(payment.getPaymentId());
    }
}
```

---

## Payment Expiry

If a payment stays in CREATED for more than 2 minutes, it's automatically expired:

```java
private static final long EXPIRY_TIME_MS = 120000; // 2 minutes

private boolean isExpired(Payment payment) {
    return (System.currentTimeMillis() - payment.getCreatedAt()) > EXPIRY_TIME_MS;
}
```

This check happens inside `processPayment()`. If expired:
1. Status is set to EXPIRED
2. An exception is thrown
3. The payment can never be processed again

**Why expiry matters:** Prevents old, forgotten payments from being processed days later when conditions may have changed.

---

## Code Location — Where This Lives

| What | File | Key Method |
|:-----|:-----|:-----------|
| State transitions | `PaymentService.java` | All methods enforce valid transitions |
| P2P processing | `PaymentService.processPayment()` | Lines 141-265 |
| Merchant auth | `PaymentService.authorizePayment()` | Lines 267-283 |
| Merchant capture | `PaymentService.capturePayment()` | Lines 285-309 |
| Refund | `PaymentService.refundPayment()` | Lines 311-334 |
| Reversal | `PaymentService.reversePayment()` | Lines 336-351 |
| Retry handling | `PaymentService.handleRetry()` | Lines 365-372 |
| Expiry check | `PaymentService.isExpired()` | Lines 361-363 |
| Retry scheduler | `PaymentRetryScheduler.java` | Lines 43-64 |
| Payment states enum | `PaymentStatus.java` | 8 values |

---

## Interview Talking Points

**Q: "Walk me through the payment lifecycle."**

> "A payment starts in CREATED. For P2P, processing it directly moves funds and goes to CAPTURED. For merchant payments, there's a two-phase flow: AUTHORIZED (funds reserved) → CAPTURED (funds transferred). If processing fails, it enters RETRYING state where a background scheduler retries every 30 seconds up to 3 times. After 3 failures, it's permanently FAILED. Payments not processed within 2 minutes auto-expire."

**Q: "What prevents invalid state transitions?"**

> "The PaymentService checks the current status before every operation. For example, `refundPayment()` checks `if (status != CAPTURED) throw`. `capturePayment()` checks `if (status != AUTHORIZED) throw`. Terminal states (FAILED, EXPIRED, REFUNDED, REVERSED) block all further transitions. This is a state machine pattern enforced at the application level."

**Q: "Why not use a state machine library?"**

> "For 8 states with clear transitions, explicit if-checks in the service layer are simpler, more debuggable, and have zero dependencies. A library like Spring State Machine adds complexity that's justified only when you have 50+ states with complex rules."
