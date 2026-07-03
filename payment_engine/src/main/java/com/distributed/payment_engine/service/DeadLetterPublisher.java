package com.distributed.payment_engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Dead Letter Queue Publisher.
 *
 * When a Kafka consumer fails to process a message after exhausting retries,
 * the message is a "poison pill" — it will block the partition forever if
 * we keep retrying. The solution is to park it in a Dead Letter Topic (DLT).
 *
 * THE PATTERN:
 * ┌──────────────────────────────────────────────────────────────┐
 * │  Consumer receives message from "payment-events"             │
 * │  Processing fails (bad data, downstream error, etc.)         │
 * │  Retry 1 → fail                                             │
 * │  Retry 2 → fail                                             │
 * │  Retry 3 → fail                                             │
 * │  ──────────────────────────────────────────────────────────  │
 * │  Send to "payment-events-dlq" with error metadata            │
 * │  Acknowledge original message → consumer moves on            │
 * └──────────────────────────────────────────────────────────────┘
 *
 * WHY NOT JUST DROP THE MESSAGE?
 * - In financial systems, losing a message = losing money.
 * - DLQ preserves the original payload + error context for investigation.
 * - An operations team can inspect, fix, and replay DLQ messages later.
 *
 * WHAT STRIPE/RAZORPAY DO:
 * - Failed webhook deliveries go to a retry queue with exponential backoff.
 * - After max retries, the event is parked and the merchant is notified.
 * - Same principle: never lose, always park.
 */
@Service
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.poller.topic:payment-events}")
    private String mainTopic;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Send a failed message to the Dead Letter Topic.
     *
     * The DLT name follows the convention: {originalTopic}-dlq
     * Example: payment-events → payment-events-dlq
     *
     * We enrich the payload with error metadata so the ops team knows
     * why it failed without digging through application logs.
     *
     * @param originalPayload The original Kafka message that failed processing
     * @param error           The exception that caused the failure
     * @param retryCount      How many times we tried before giving up
     */
    public void sendToDeadLetter(String originalPayload, Throwable error, int retryCount) {
        String dlqTopic = mainTopic + "-dlq";

        // Build an enriched DLQ message with error context
        String dlqPayload = String.format(
                "{\"originalPayload\":%s,\"error\":\"%s\",\"retryCount\":%d,\"failedAt\":\"%s\",\"topic\":\"%s\"}",
                originalPayload,
                escapeJson(error.getMessage()),
                retryCount,
                java.time.Instant.now().toString(),
                mainTopic
        );

        try {
            kafkaTemplate.send(dlqTopic, dlqPayload).join();
            log.warn("[DLQ] Message sent to dead-letter topic '{}'. Error: {}. Retries exhausted: {}",
                    dlqTopic, error.getMessage(), retryCount);
        } catch (Exception e) {
            // If even the DLQ publish fails, log at ERROR level.
            // The original message is already acknowledged at this point.
            // In production, this would trigger a PagerDuty alert.
            log.error("[DLQ] CRITICAL — Failed to publish to DLQ topic '{}'. " +
                    "Original payload may be lost. Error: {}", dlqTopic, e.getMessage());
        }
    }

    /**
     * Escape special characters in a string for safe JSON embedding.
     */
    private String escapeJson(String text) {
        if (text == null) return "unknown";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }
}
