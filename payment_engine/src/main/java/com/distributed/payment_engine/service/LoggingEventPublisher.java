package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Logging Event Publisher (Development Implementation).
 *
 * Publishes payment events by logging them to the console.
 * This is the default implementation used during development and testing.
 *
 * In production , this will be replaced by KafkaEventPublisher
 * which sends events to a real Kafka broker.
 *
 * WHY THIS EXISTS:
 * We want to wire event publishing into PaymentService NOW,
 * without waiting for Kafka setup. This lets us:
 *   1. Verify events are generated at the right moments
 *   2. See event payloads in the logs
 *   3. Write tests that verify event publishing
 *   4. Swap to Kafka later with zero changes to PaymentService
 */
@Service
public class LoggingEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(PaymentEvent event) {
        log.info("[EVENT] {} | paymentId={} | {} → {} | amount={} | eventId={}",
                event.getEventType(),
                event.getPaymentId(),
                event.getPreviousStatus() != null ? event.getPreviousStatus() : "NEW",
                event.getCurrentStatus(),
                event.getAmount(),
                event.getEventId());
    }
}
