package com.distributed.payment_engine.model.enums;

/** Outbox Event Publishing Status.
 *
 * Tracks the lifecycle of an event in the outbox table:
 *
 *   PENDING → PUBLISHED (happy path)
 *   PENDING → FAILED    (Kafka publish failed after retries)
 *
 * The poller picks up PENDING events,
 * publishes them to Kafka, and updates the status.
 */
public enum OutboxStatus {

    /**
     * Event is waiting to be published to Kafka.
     * The poller will pick this up on its next run.
     */
    PENDING,

    /**
     * Event was successfully published to Kafka.
     * Can be cleaned up after a retention period.
     */
    PUBLISHED,

    /**
     * Event failed to publish after maximum retries.
     * Requires manual investigation.
     */
    FAILED
}
