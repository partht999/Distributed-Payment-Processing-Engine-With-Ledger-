package com.distributed.payment_engine.model.event;

import com.distributed.payment_engine.model.enums.PaymentEventType;
import com.distributed.payment_engine.model.enums.PaymentStatus;
import com.distributed.payment_engine.model.enums.PaymentType;

import java.time.Instant;
import java.util.UUID;

/** Payment Domain Event.
 *
 * This is the core event model that will be published to Kafka.
 * Every significant state transition in a payment produces one of these events.
 *
 * DESIGN PRINCIPLES:
 *
 * 1. IMMUTABLE — Once created, an event never changes.
 *    Events represent facts that happened in the past ("payment was captured").
 *    You cannot un-capture a payment by modifying the event.
 *
 * 2. SELF-CONTAINED — Contains all info a consumer needs.
 *    A consumer should not need to call back to the payment service
 *    to understand what happened. The event has the payment ID, amount,
 *    wallets, status, and timestamps.
 *
 * 3. UNIQUE — Each event has a UUID (eventId).
 *    Consumers use this for their own idempotency — if they receive
 *    the same event twice (Kafka "at-least-once"), they can deduplicate.
 *
 * 4. ORDERED — Each event has a timestamp.
 *    Consumers can reconstruct the payment timeline by sorting events.
 *
 * EXAMPLE EVENT (JSON, as it will appear in Kafka):
 * {
 *   "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
 *   "eventType": "PAYMENT_CAPTURED",
 *   "paymentId": 42,
 *   "fromWalletId": 1,
 *   "toWalletId": 2,
 *   "amount": 500,
 *   "paymentType": "P2P",
 *   "previousStatus": "CREATED",
 *   "currentStatus": "CAPTURED",
 *   "idempotencyKey": "txn-abc-123",
 *   "occurredAt": "2026-06-19T23:20:00.123Z"
 * }
 */
public class PaymentEvent {

    /**
     * Unique identifier for this event.
     * Used by consumers for idempotency (deduplicate repeated deliveries).
     */
    private final String eventId;

    /**
     * The type of event (PAYMENT_CREATED, PAYMENT_CAPTURED, etc.)
     */
    private final PaymentEventType eventType;

    /**
     * The payment this event is about.
     */
    private final Long paymentId;

    /**
     * Sender's wallet ID.
     */
    private final Long fromWalletId;

    /**
     * Receiver's wallet ID.
     */
    private final Long toWalletId;

    /**
     * Payment amount in smallest currency unit (e.g., paise/cents).
     */
    private final Long amount;

    /**
     * Type of payment (P2P or MERCHANT).
     */
    private final PaymentType paymentType;

    /**
     * The status BEFORE this event happened.
     * null for PAYMENT_CREATED (there is no previous status).
     */
    private final PaymentStatus previousStatus;

    /**
     * The status AFTER this event happened.
     */
    private final PaymentStatus currentStatus;

    /**
     * The client-provided idempotency key.
     * Included so consumers can correlate events with client requests.
     */
    private final String idempotencyKey;

    /**
     * When this event occurred (ISO 8601 UTC).
     */
    private final Instant occurredAt;

    /**
     * Private constructor — use the static factory methods below.
     */
    private PaymentEvent(PaymentEventType eventType, Long paymentId,
                         Long fromWalletId, Long toWalletId, Long amount,
                         PaymentType paymentType, PaymentStatus previousStatus,
                         PaymentStatus currentStatus, String idempotencyKey) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.paymentId = paymentId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.paymentType = paymentType;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
        this.idempotencyKey = idempotencyKey;
        this.occurredAt = Instant.now();
    }

    // ═══════════════════════════════════════════════
    // STATIC FACTORY METHODS — one per event type
    // ═══════════════════════════════════════════════

    /**
     * Create a PAYMENT_CREATED event.
     */
    public static PaymentEvent created(Long paymentId, Long fromWalletId, Long toWalletId,
                                        Long amount, PaymentType paymentType, String idempotencyKey) {
        return new PaymentEvent(PaymentEventType.PAYMENT_CREATED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                null, PaymentStatus.CREATED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_AUTHORIZED event (merchant payment money blocked).
     */
    public static PaymentEvent authorized(Long paymentId, Long fromWalletId, Long toWalletId,
                                           Long amount, PaymentType paymentType, String idempotencyKey,
                                           PaymentStatus previousStatus) {
        return new PaymentEvent(PaymentEventType.PAYMENT_AUTHORIZED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                previousStatus, PaymentStatus.AUTHORIZED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_CAPTURED event (money transferred successfully).
     */
    public static PaymentEvent captured(Long paymentId, Long fromWalletId, Long toWalletId,
                                         Long amount, PaymentType paymentType, String idempotencyKey,
                                         PaymentStatus previousStatus) {
        return new PaymentEvent(PaymentEventType.PAYMENT_CAPTURED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                previousStatus, PaymentStatus.CAPTURED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_FAILED event (permanently failed).
     */
    public static PaymentEvent failed(Long paymentId, Long fromWalletId, Long toWalletId,
                                       Long amount, PaymentType paymentType, String idempotencyKey,
                                       PaymentStatus previousStatus) {
        return new PaymentEvent(PaymentEventType.PAYMENT_FAILED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                previousStatus, PaymentStatus.FAILED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_RETRYING event (temporary failure, will retry).
     */
    public static PaymentEvent retrying(Long paymentId, Long fromWalletId, Long toWalletId,
                                         Long amount, PaymentType paymentType, String idempotencyKey,
                                         PaymentStatus previousStatus) {
        return new PaymentEvent(PaymentEventType.PAYMENT_RETRYING, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                previousStatus, PaymentStatus.RETRYING, idempotencyKey);
    }

    /**
     * Create a PAYMENT_EXPIRED event.
     */
    public static PaymentEvent expired(Long paymentId, Long fromWalletId, Long toWalletId,
                                        Long amount, PaymentType paymentType, String idempotencyKey,
                                        PaymentStatus previousStatus) {
        return new PaymentEvent(PaymentEventType.PAYMENT_EXPIRED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                previousStatus, PaymentStatus.EXPIRED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_REFUNDED event (money returned).
     */
    public static PaymentEvent refunded(Long paymentId, Long fromWalletId, Long toWalletId,
                                         Long amount, PaymentType paymentType, String idempotencyKey) {
        return new PaymentEvent(PaymentEventType.PAYMENT_REFUNDED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                PaymentStatus.CAPTURED, PaymentStatus.REFUNDED, idempotencyKey);
    }

    /**
     * Create a PAYMENT_REVERSED event (authorization cancelled).
     */
    public static PaymentEvent reversed(Long paymentId, Long fromWalletId, Long toWalletId,
                                         Long amount, PaymentType paymentType, String idempotencyKey) {
        return new PaymentEvent(PaymentEventType.PAYMENT_REVERSED, paymentId,
                fromWalletId, toWalletId, amount, paymentType,
                PaymentStatus.AUTHORIZED, PaymentStatus.REVERSED, idempotencyKey);
    }

    // ═══════════════════════════════════════════════
    // GETTERS (immutable — no setters)
    // ═══════════════════════════════════════════════

    public String getEventId() { return eventId; }
    public PaymentEventType getEventType() { return eventType; }
    public Long getPaymentId() { return paymentId; }
    public Long getFromWalletId() { return fromWalletId; }
    public Long getToWalletId() { return toWalletId; }
    public Long getAmount() { return amount; }
    public PaymentType getPaymentType() { return paymentType; }
    public PaymentStatus getPreviousStatus() { return previousStatus; }
    public PaymentStatus getCurrentStatus() { return currentStatus; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getOccurredAt() { return occurredAt; }

    @Override
    public String toString() {
        return "PaymentEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType=" + eventType +
                ", paymentId=" + paymentId +
                ", amount=" + amount +
                ", previousStatus=" + previousStatus +
                ", currentStatus=" + currentStatus +
                ", occurredAt=" + occurredAt +
                '}';
    }
}
