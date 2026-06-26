package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.OutboxEvent;
import com.distributed.payment_engine.model.event.PaymentEvent;
import com.distributed.payment_engine.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Outbox Event Publisher — Transactional Outbox Pattern Implementation.
 *
 * This replaces LoggingEventPublisher as the PRIMARY event publisher.
 * Instead of just logging events, it saves them to the outbox_events table
 * in the SAME database transaction as the payment change.
 *
 * HOW IT WORKS:
 *   1. PaymentService calls publishEventSafely(event)
 *   2. This publisher converts the PaymentEvent to JSON
 *   3. Saves an OutboxEvent row (status = PENDING)
 *   4. Because PaymentService is @Transactional, the outbox INSERT is part
 *      of the same transaction as the payment UPDATE
 *   5. If the payment fails and rolls back, the outbox row also rolls back
 *   6. If the payment commits, the outbox row also commits
 *
 * RESULT: We GUARANTEE that every committed payment has a matching event
 * waiting in the outbox table. No dual-write problem. No lost events.
 *
 * The OutboxPollerService reads PENDING events and publishes to Kafka.
 *
 * @Primary annotation makes Spring prefer this over LoggingEventPublisher
 * when autowiring PaymentEventPublisher.
 */
@Service
@Primary
public class OutboxEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    @Autowired
    private OutboxEventRepository outboxRepo;

    @Override
    public void publish(PaymentEvent event) {
        // Convert PaymentEvent to JSON string for the outbox payload
        String payload = toJson(event);

        // Create an OutboxEvent row — status = PENDING
        OutboxEvent outboxEvent = new OutboxEvent(
                event.getEventId(),
                event.getEventType().name(),
                "PAYMENT",
                event.getPaymentId(),
                payload
        );

        // Save to the outbox table — this is part of the SAME @Transactional
        // as the payment save in PaymentService
        outboxRepo.save(outboxEvent);

        log.info("[Outbox] Saved event {} for payment {} (status=PENDING)",
                event.getEventType(), event.getPaymentId());
    }

    /**
     * Convert a PaymentEvent to a JSON string.
     *
     * We build JSON manually here to avoid adding Jackson ObjectMapper
     * as a dependency to this service. In production, you'd use ObjectMapper.
     * For our learning purposes, this is clearer and has zero dependencies.
     */
    private String toJson(PaymentEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"eventId\":\"").append(event.getEventId()).append("\",");
        sb.append("\"eventType\":\"").append(event.getEventType()).append("\",");
        sb.append("\"paymentId\":").append(event.getPaymentId()).append(",");
        sb.append("\"fromWalletId\":").append(event.getFromWalletId()).append(",");
        sb.append("\"toWalletId\":").append(event.getToWalletId()).append(",");
        sb.append("\"amount\":").append(event.getAmount()).append(",");
        sb.append("\"paymentType\":\"").append(event.getPaymentType()).append("\",");
        sb.append("\"previousStatus\":").append(
                event.getPreviousStatus() != null ? "\"" + event.getPreviousStatus() + "\"" : "null"
        ).append(",");
        sb.append("\"currentStatus\":\"").append(event.getCurrentStatus()).append("\",");
        sb.append("\"idempotencyKey\":\"").append(event.getIdempotencyKey()).append("\",");
        sb.append("\"occurredAt\":\"").append(event.getOccurredAt()).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
