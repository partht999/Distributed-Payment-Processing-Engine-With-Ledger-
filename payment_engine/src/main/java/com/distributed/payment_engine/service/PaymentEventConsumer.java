package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.ProcessedEvent;
import com.distributed.payment_engine.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment Event Consumer — Kafka Consumer with retry + Dead Letter Queue.
 *
 * Listens to the "payment-events" topic and processes incoming events.
 * Implements three layers of protection:
 *
 * 1. CONSUMER IDEMPOTENCY — processed_events table prevents re-processing
 * 2. RETRY WITH BACKOFF   — retries up to N times before giving up
 * 3. DEAD LETTER QUEUE    — failed messages are parked in DLQ for investigation
 *
 * THE FLOW:
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Kafka → onPaymentEvent(message)                                │
 * │  ├── Check processed_events (idempotency) → skip if duplicate   │
 * │  ├── Extract fields, dispatch webhook                           │
 * │  ├── Mark as processed in DB                                    │
 * │  └── On failure:                                                │
 * │      ├── Retry up to MAX_RETRIES with exponential backoff       │
 * │      └── After exhaustion → DeadLetterPublisher.sendToDeadLetter│
 * └─────────────────────────────────────────────────────────────────┘
 */
@Service
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    @Value("${consumer.retry.max-attempts:3}")
    private int maxRetries;

    @Value("${consumer.retry.backoff-ms:1000}")
    private long backoffMs;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    @Autowired
    private DeadLetterPublisher deadLetterPublisher;

    /**
     * Process a payment event received from Kafka.
     *
     * Uses application-level retry with exponential backoff before
     * routing to the Dead Letter Queue. This is different from
     * Kafka's built-in retry (which just re-delivers the same offset).
     *
     * @param message The JSON payload of the event
     */
    @Transactional
    @KafkaListener(
            topics = "${outbox.poller.topic:payment-events}",
            groupId = "${spring.kafka.consumer.group-id:payment-engine-group}"
    )
    public void onPaymentEvent(String message) {
        log.info("[KafkaConsumer] Received payment event: {}", message);

        String eventId = extractEventId(message);
        String eventType = extractEventType(message);

        // Guard: skip events with missing metadata
        if (eventId == null || eventType == null) {
            log.warn("[KafkaConsumer] Skipping event — missing eventId or eventType");
            return;
        }

        // Idempotency check: skip already-processed events
        if (processedEventRepo.existsByEventId(eventId)) {
            log.info("[KafkaConsumer] Duplicate event {} already processed. Skipping.", eventId);
            return;
        }

        // Attempt processing with retry + DLQ fallback
        processWithRetry(message, eventId, eventType);
    }

    /**
     * Retry processing up to maxRetries with exponential backoff.
     * After exhaustion, route to DLQ.
     *
     * EXPONENTIAL BACKOFF:
     *   Attempt 1 → wait 1000ms
     *   Attempt 2 → wait 2000ms
     *   Attempt 3 → wait 4000ms
     *   → DLQ
     *
     * WHY APPLICATION-LEVEL RETRY?
     * Kafka's built-in retry just re-polls the same offset, which can
     * block the entire partition. Application-level retry lets us:
     * - Add backoff delays
     * - Count attempts
     * - Route to DLQ after exhaustion
     * - Continue consuming other messages
     */
    private void processWithRetry(String message, String eventId, String eventType) {
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                processEvent(message, eventId, eventType);
                return; // Success — exit retry loop
            } catch (Exception e) {
                lastError = e;
                log.warn("[KafkaConsumer] Attempt {}/{} failed for event {}. Error: {}",
                        attempt, maxRetries, eventId, e.getMessage());

                if (attempt < maxRetries) {
                    long delay = backoffMs * (1L << (attempt - 1)); // Exponential: 1s, 2s, 4s
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted — send to Dead Letter Queue
        log.error("[KafkaConsumer] All {} retries exhausted for event {}. Routing to DLQ.",
                maxRetries, eventId);
        deadLetterPublisher.sendToDeadLetter(message, lastError, maxRetries);
    }

    /**
     * Core event processing logic — separated for testability.
     * This is the actual business logic that can fail.
     */
    private void processEvent(String message, String eventId, String eventType) {
        // Extract toWalletId from JSON payload for webhook routing
        Long toWalletId = extractToWalletId(message);

        if (toWalletId != null) {
            boolean delivered = webhookService.dispatch(toWalletId, message);
            if (!delivered) {
                log.warn("[KafkaConsumer] Webhook delivery failed for wallet {}", toWalletId);
            }
        }

        // Mark event as processed in the database (idempotency record)
        ProcessedEvent processedEvent = new ProcessedEvent(eventId, eventType);
        processedEventRepo.save(processedEvent);
        log.info("[KafkaConsumer] Successfully processed event {} ({})", eventId, eventType);
    }

    // ─── JSON Field Extraction Helpers ───

    private String extractEventId(String json) {
        return extractStringField(json, "eventId");
    }

    private String extractEventType(String json) {
        return extractStringField(json, "eventType");
    }

    /**
     * Extract a string field value from JSON.
     * Uses simple string parsing — in production, use Jackson ObjectMapper.
     */
    private String extractStringField(String json, String fieldName) {
        try {
            String key = "\"" + fieldName + "\":\"";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Could not extract {} from event", fieldName);
            return null;
        }
    }

    /**
     * Extract toWalletId (numeric) from JSON payload.
     */
    private Long extractToWalletId(String json) {
        try {
            String key = "\"toWalletId\":";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            return Long.parseLong(json.substring(start, end));
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Could not extract toWalletId from event");
            return null;
        }
    }
}
