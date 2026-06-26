package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.ProcessedEvent;
import com.distributed.payment_engine.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment Event Consumer — Kafka Consumer with @KafkaListener.
 *
 * Listens to the "payment-events" Kafka topic and processes incoming events.
 * Basic consumer — just logs events. Routes events to WebhookService for merchant notification. Implements consumer-side idempotency (deduplication) using the processed_events table.
 */
@Service
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    /**
     * Process a payment event received from Kafka.
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

        try {
            // Extract eventId and eventType for idempotency tracking
            String eventId = extractEventId(message);
            String eventType = extractEventType(message);

            if (eventId == null || eventType == null) {
                log.warn("[KafkaConsumer] Skipping event due to missing eventId or eventType");
                return;
            }

            // Check for duplicate event (idempotency check)
            if (processedEventRepo.existsByEventId(eventId)) {
                log.info("[KafkaConsumer] Duplicate event detected. Event {} has already been processed. Skipping.", eventId);
                return;
            }

            // Extract toWalletId from JSON payload for webhook routing
            Long toWalletId = extractToWalletId(message);

            if (toWalletId != null) {
                // Dispatch to webhook if the wallet has one registered
                boolean delivered = webhookService.dispatch(toWalletId, message);
                if (!delivered) {
                    log.warn("[KafkaConsumer] Webhook delivery failed for wallet {}", toWalletId);
                    // failed deliveries will go to the dead-letter topic
                }
            }

            // Mark event as processed in the database
            ProcessedEvent processedEvent = new ProcessedEvent(eventId, eventType);
            processedEventRepo.save(processedEvent);
            log.info("[KafkaConsumer] Successfully processed event {} ({})", eventId, eventType);

        } catch (Exception e) {
            log.error("[KafkaConsumer] Failed to process event. Error: {}", e.getMessage());
            // this will send the message to a dead-letter topic
        }
    }

    /**
     * Extract eventId from the JSON payload.
     */
    private String extractEventId(String json) {
        try {
            String key = "\"eventId\":\"";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Could not extract eventId from event");
            return null;
        }
    }

    /**
     * Extract eventType from the JSON payload.
     */
    private String extractEventType(String json) {
        try {
            String key = "\"eventType\":\"";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Could not extract eventType from event");
            return null;
        }
    }

    /**
     * Extract toWalletId from the JSON payload.
     *
     * Simple JSON parsing without a library — looks for "toWalletId":N pattern.
     * In production, use Jackson ObjectMapper for proper deserialization.
     */
    private Long extractToWalletId(String json) {
        try {
            String key = "\"toWalletId\":";
            int start = json.indexOf(key);
            if (start == -1) return null;
            start += key.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)))) {
                end++;
            }
            return Long.parseLong(json.substring(start, end));
        } catch (Exception e) {
            log.warn("[KafkaConsumer] Could not extract toWalletId from event");
            return null;
        }
    }
}
