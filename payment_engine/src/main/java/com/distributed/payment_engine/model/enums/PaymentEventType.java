package com.distributed.payment_engine.model.enums;

/** Payment Event Types for Event-Driven Architecture.
 *
 * Each event type maps to a significant state transition in the payment lifecycle.
 * These events will be published to Kafka  so downstream services
 * (analytics, notifications, fraud detection) can react to payment changes.
 *
 * EVENT FLOW:
 *   PAYMENT_CREATED → PAYMENT_AUTHORIZED → PAYMENT_CAPTURED
 *                                       ↘ PAYMENT_REVERSED
 *                   → PAYMENT_CAPTURED (P2P skips authorization)
 *                   → PAYMENT_FAILED
 *                   → PAYMENT_EXPIRED
 *   PAYMENT_CAPTURED → PAYMENT_REFUNDED
 *   Any processing attempt → PAYMENT_RETRYING
 */
public enum PaymentEventType {

    /**
     * A new payment was created (status: CREATED).
     * Consumers: Analytics (track payment volume), Notification (send receipt).
     */
    PAYMENT_CREATED,

    /**
     * A merchant payment was authorized (money blocked, not yet captured).
     * Consumers: Merchant dashboard, Risk engine.
     */
    PAYMENT_AUTHORIZED,

    /**
     * Payment was successfully captured (money transferred).
     * This is the "money moved" event — the most critical one.
     * Consumers: Accounting, Settlement, Notifications.
     */
    PAYMENT_CAPTURED,

    /**
     * Payment processing failed permanently (max retries exceeded).
     * Consumers: Alert system, Customer support.
     */
    PAYMENT_FAILED,

    /**
     * Payment processing failed temporarily — will retry.
     * Consumers: Monitoring dashboard, Alert system (if too many retries).
     */
    PAYMENT_RETRYING,

    /**
     * Payment expired before processing (TTL exceeded).
     * Consumers: Cleanup service, Analytics.
     */
    PAYMENT_EXPIRED,

    /**
     * A captured payment was refunded (money returned to sender).
     * Consumers: Accounting (reverse entries), Notifications.
     */
    PAYMENT_REFUNDED,

    /**
     * An authorized payment was reversed (hold released, no money moved).
     * Consumers: Merchant dashboard, Risk engine.
     */
    PAYMENT_REVERSED
}
