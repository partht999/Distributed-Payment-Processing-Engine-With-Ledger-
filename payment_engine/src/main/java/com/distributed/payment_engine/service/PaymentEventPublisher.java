package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.event.PaymentEvent;

/** Payment Event Publisher Interface.
 *
 * This interface defines HOW payment events are published,
 * without coupling to any specific messaging system.
 *
 * IMPLEMENTATIONS:
 *   - LoggingEventPublisher : Logs events to console (development)
 *   - KafkaEventPublisher : Publishes to Kafka (production)
 *
 * WHY AN INTERFACE?
 * This follows the Dependency Inversion Principle (SOLID).
 * PaymentService depends on this interface, not on Kafka directly.
 * This means:
 *   - We can develop and test without Kafka running
 *   - We can swap Kafka for RabbitMQ, SQS, etc. without changing PaymentService
 *   - We can mock this in unit tests
 */
public interface PaymentEventPublisher {

    /**
     * Publish a payment event.
     *
     * @param event The event to publish
     */
    void publish(PaymentEvent event);
}
