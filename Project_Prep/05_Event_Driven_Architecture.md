# Part 5: Event-Driven Architecture — Outbox, Kafka, Webhooks, DLQ

> This explains the event pipeline: how payment events flow from the database to Kafka to merchant webhooks, with zero event loss.

---

## The Dual-Write Problem

When a payment is captured, we need to do TWO things:
1. **Update the database** (payment status → CAPTURED)
2. **Publish an event** (notify downstream systems)

**The naive approach:**
```java
paymentRepo.save(payment);           // Step 1: DB write
kafkaTemplate.send("payment-events", event);  // Step 2: Kafka write
```

**Why this is broken:**
- If Step 1 succeeds but Step 2 fails (Kafka down) → Payment saved but no event published. **Event lost.**
- If Step 2 succeeds but Step 1 fails (DB error) → Event published but payment not saved. **Ghost event.**
- You can't put a DB write and a Kafka write in the same ACID transaction — they're different systems.

This is the **dual-write problem**. It's one of the hardest problems in distributed systems.

---

## The Solution: Transactional Outbox Pattern

Instead of writing to Kafka directly, we write the event to a DATABASE TABLE in the SAME transaction as the payment:

```java
@Transactional  // ← BOTH writes are in ONE transaction
public Payment processPayment(Long paymentId) {
    payment.setStatus(PaymentStatus.CAPTURED);
    paymentRepo.save(payment);          // DB write #1: payment
    
    outboxEventPublisher.publish(event); // DB write #2: outbox_events table
    // ↑ This does NOT write to Kafka!
    // ↑ It saves an OutboxEvent row in PostgreSQL (status = PENDING)
    
    // COMMIT → Both succeed or both fail. Atomically guaranteed.
}
```

**Then, a separate poller picks up PENDING events and publishes them to Kafka:**

```
Every 5 seconds:
  SELECT * FROM outbox_events WHERE status = 'PENDING'
  → For each event: kafkaTemplate.send(...) → mark as PUBLISHED
```

---

## The Four Components

### Component 1: OutboxEventPublisher — "Save Event to DB"

```java
@Service
@Primary  // ← Spring prefers this over LoggingEventPublisher
public class OutboxEventPublisher implements PaymentEventPublisher {

    public void publish(PaymentEvent event) {
        String payload = toJson(event);  // Convert to JSON string
        
        OutboxEvent outboxEvent = new OutboxEvent(
            event.getEventId(),      // UUID
            event.getEventType(),    // "PAYMENT_CAPTURED"
            "PAYMENT",              // aggregate type
            event.getPaymentId(),   // aggregate ID
            payload                 // full JSON payload
        );
        
        outboxRepo.save(outboxEvent);  // INSERT into outbox_events (status = PENDING)
    }
}
```

**Key insight:** This runs inside `PaymentService`'s `@Transactional`. If the payment save fails and rolls back, the outbox event also rolls back. **Zero orphan events.**

---

### Component 2: OutboxPollerService — "DB → Kafka Relay"

```java
@Scheduled(fixedDelayString = "${outbox.poller.interval-ms:5000}")
public void pollAndPublish() {
    // 1. Find all pending events
    List<OutboxEvent> pendingEvents = 
        outboxRepo.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
    
    if (pendingEvents.isEmpty()) return;  // Most polls hit this — cheap query
    
    for (OutboxEvent event : pendingEvents) {
        try {
            // 2. Publish to Kafka
            kafkaTemplate.send(topicName,
                String.valueOf(event.getAggregateId()),  // Key = paymentId
                event.getPayload()                        // Value = JSON
            ).join();  // Block until Kafka acknowledges
            
            // 3. Mark as published
            event.markPublished();
            outboxRepo.save(event);
            
        } catch (Exception e) {
            // Kafka failed — mark as FAILED, will retry next poll
            event.markFailed();
            outboxRepo.save(event);
        }
    }
}
```

**Kafka key = paymentId:** This ensures all events for the same payment go to the same Kafka partition, preserving event order (CREATED → CAPTURED → REFUNDED in order).

---

### Component 3: PaymentEventConsumer — "Kafka → Process"

```java
@KafkaListener(topics = "payment-events", groupId = "payment-engine-group")
public void onPaymentEvent(String message) {
    String eventId = extractEventId(message);
    
    // CONSUMER IDEMPOTENCY: Skip already-processed events
    if (processedEventRepo.existsByEventId(eventId)) {
        return;  // Already processed — Kafka delivered this twice
    }
    
    // Process with retry + DLQ fallback
    processWithRetry(message, eventId, eventType);
}
```

**Why consumer idempotency?**

Kafka guarantees "at-least-once" delivery. If the consumer crashes after processing but before committing the offset, Kafka re-delivers the message. The `processed_events` table prevents double-processing.

**Retry with exponential backoff:**

```java
for (int attempt = 1; attempt <= maxRetries; attempt++) {
    try {
        processEvent(message, eventId, eventType);
        return;  // Success
    } catch (Exception e) {
        long delay = backoffMs * (1L << (attempt - 1));  // 1s, 2s, 4s
        Thread.sleep(delay);
    }
}
// All retries failed → send to DLQ
deadLetterPublisher.sendToDeadLetter(message, lastError, maxRetries);
```

---

### Component 4: WebhookService — "Notify the Merchant"

When a payment event is processed, the consumer calls `webhookService.dispatch()`:

```java
public boolean dispatch(Long toWalletId, String eventJson) {
    // 1. Look up webhook config for this wallet
    Optional<WebhookConfig> config = webhookRepo.findByWalletIdAndActiveTrue(toWalletId);
    if (config.isEmpty()) return true;  // No webhook registered — that's fine
    
    // 2. Compute HMAC-SHA256 signature
    String signature = computeHmac(eventJson, config.getSecret());
    
    // 3. POST the event to the merchant's URL
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(config.getWebhookUrl()))
        .header("Content-Type", "application/json")
        .header("X-Signature", signature)        // ← Merchant verifies this
        .header("X-Event-Source", "payment-engine")
        .POST(HttpRequest.BodyPublishers.ofString(eventJson))
        .build();
    
    HttpResponse<String> response = httpClient.send(request, ...);
    return response.statusCode() >= 200 && response.statusCode() < 300;
}
```

**HMAC-SHA256 signature:** The merchant stores the secret we gave them. When they receive a webhook, they compute `HMAC-SHA256(body, secret)` and compare it to the `X-Signature` header. If they match, the webhook is authentic. This prevents attackers from sending fake webhooks.

---

### Component 5: DeadLetterPublisher — "Park Failed Messages"

When all retries are exhausted, the message goes to the DLQ:

```java
public void sendToDeadLetter(String originalPayload, Throwable error, int retryCount) {
    String dlqTopic = mainTopic + "-dlq";  // "payment-events-dlq"
    
    // Enrich with error metadata
    String dlqPayload = String.format(
        "{\"originalPayload\":%s,\"error\":\"%s\",\"retryCount\":%d,\"failedAt\":\"%s\"}",
        originalPayload, error.getMessage(), retryCount, Instant.now()
    );
    
    kafkaTemplate.send(dlqTopic, dlqPayload).join();
}
```

**Why not just drop the message?**
- In financial systems, losing a message = losing money
- DLQ preserves the original payload + error context
- An operations team can inspect, fix, and replay DLQ messages later
- This is exactly what Stripe and Razorpay do

---

## The Complete Event Pipeline

```
┌─────────────┐     ┌───────────────────┐     ┌────────────────┐
│ Payment      │     │ outbox_events     │     │ Kafka          │
│ Service      │────▶│ table (PENDING)   │────▶│ payment-events │
│              │     │                   │     │ topic          │
│ @Transactional│    │ OutboxPoller      │     └───────┬────────┘
│ saves both   │     │ (every 5 sec)     │             │
└─────────────┘     └───────────────────┘             │
                                                       ▼
┌─────────────────────────────────────────────────────────────────┐
│  PaymentEventConsumer (@KafkaListener)                          │
│                                                                 │
│  1. Check processed_events → skip if duplicate                  │
│  2. Retry up to 3 times with exponential backoff (1s, 2s, 4s)  │
│  3. Call WebhookService.dispatch() → HMAC-signed POST           │
│  4. Save to processed_events (idempotency record)               │
│  5. On failure: → DeadLetterPublisher → payment-events-dlq      │
└─────────────────────────────────────────────────────────────────┘
```

---

## Graceful Degradation: Kafka Down

If Kafka is unavailable:
1. Events stay safely in `outbox_events` table (status = PENDING)
2. The poller marks them as FAILED
3. Next poll cycle, it picks them up and retries
4. When Kafka comes back, events flow normally
5. **No events are ever lost** — they're safely persisted in PostgreSQL

---

## Interview Talking Points

**Q: "How do you handle the dual-write problem?"**

> "We use the Transactional Outbox pattern. Instead of writing to both PostgreSQL and Kafka in the same operation, we write the event to an outbox_events table in the SAME database transaction as the payment. A separate poller reads PENDING events every 5 seconds and publishes them to Kafka. This guarantees that every committed payment has a matching event — no dual-write risk."

**Q: "What happens if Kafka is down?"**

> "Events stay safely in the outbox_events table with status PENDING. The poller retries on the next cycle. When Kafka comes back, all pending events are published. Zero event loss — the database is the buffer."

**Q: "How do you handle duplicate Kafka messages?"**

> "Consumer-side idempotency. We have a processed_events table with a UNIQUE constraint on eventId. Before processing a message, we check if that eventId already exists. If it does, we skip it. This handles Kafka's at-least-once delivery guarantee."

**Q: "What are webhooks and how do you secure them?"**

> "When a payment event occurs, we POST the event JSON to the merchant's registered webhook URL. We sign the payload with HMAC-SHA256 using a shared secret. The merchant computes the same hash on their end and compares it to the X-Signature header. If they match, the webhook is authentic. This is the same pattern Stripe and Razorpay use."
