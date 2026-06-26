package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.OutboxEvent;
import com.distributed.payment_engine.model.enums.OutboxStatus;
import com.distributed.payment_engine.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Outbox Poller Service — The "Relay" in the Transactional Outbox Pattern.
 *
 * This service runs on a fixed schedule (every 5 seconds by default).
 * Each time it wakes up, it:
 *   1. Queries the outbox_events table for PENDING events
 *   2. Publishes each event to Kafka using KafkaTemplate
 *   3. Marks the event as PUBLISHED (or FAILED if Kafka rejects it)
 *
 * WHY A POLLER AND NOT A TRIGGER?
 * We could use database triggers or CDC (Change Data Capture) to detect
 * new outbox rows. But a poller is:
 *   - Simple to implement and understand
 *   - Easy to debug (just check the logs)
 *   - Resilient (if it crashes, it restarts and picks up where it left off)
 *   - Good enough for most systems (5-second delay is fine for notifications)
 *
 * AT-LEAST-ONCE DELIVERY:
 * If the poller publishes to Kafka but crashes before marking PUBLISHED,
 * the event stays PENDING and will be sent again. This means Kafka consumers
 * might receive the same event twice → that's why consumers need idempotency
 * . This is the "at-least-once" guarantee.
 *
 * KAFKA TOPIC: payment-events
 * KAFKA KEY: paymentId (ensures all events for the same payment go to the same partition)
 * KAFKA VALUE: the JSON payload from the outbox_events row
 */
@Service
public class OutboxPollerService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollerService.class);

    @Autowired
    private OutboxEventRepository outboxRepo;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.poller.topic:payment-events}")
    private String topicName;

    /**
     * Poll for PENDING outbox events and publish them to Kafka.
     *
     * @Scheduled runs this method every N milliseconds (configured in application.properties).
     * If there are no PENDING events, this method returns immediately (cheap DB query).
     */
    @Scheduled(fixedDelayString = "${outbox.poller.interval-ms:5000}")
    public void pollAndPublish() {
        List<OutboxEvent> pendingEvents =
                outboxRepo.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return; // Nothing to do — most polls will hit this path
        }

        log.info("[OutboxPoller] Found {} pending events to publish", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                publishToKafka(event);
                event.markPublished();
                outboxRepo.save(event);

                log.info("[OutboxPoller] Published event {} ({}) for payment {}",
                        event.getEventId(), event.getEventType(), event.getAggregateId());

            } catch (Exception e) {
                log.error("[OutboxPoller] Failed to publish event {} for payment {}. Error: {}",
                        event.getEventId(), event.getAggregateId(), e.getMessage());
                event.markFailed();
                outboxRepo.save(event);
            }
        }
    }

    /**
     * Publish a single event to Kafka.
     *
     * Kafka key = aggregateId (paymentId) → ensures ordering per payment.
     * All events for Payment #42 go to the same Kafka partition,
     * so consumers see CREATED → CAPTURED in order.
     *
     * Kafka value = the JSON payload stored in the outbox row.
     */
    private void publishToKafka(OutboxEvent event) {
        if (kafkaTemplate == null) {
            // Kafka not available (e.g., in tests) — log and mark published anyway
            log.warn("[OutboxPoller] KafkaTemplate not available — logging event instead: {} {}",
                    event.getEventType(), event.getAggregateId());
            return;
        }

        // Send to Kafka synchronously (block until ack)
        // Key = paymentId (partition routing for ordering)
        // Value = JSON payload
        kafkaTemplate.send(topicName,
                String.valueOf(event.getAggregateId()),
                event.getPayload()
        ).join(); // .join() blocks until Kafka acknowledges receipt
    }
}
