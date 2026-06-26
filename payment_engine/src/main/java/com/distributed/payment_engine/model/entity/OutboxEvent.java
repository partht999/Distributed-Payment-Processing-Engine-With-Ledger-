package com.distributed.payment_engine.model.entity;

import com.distributed.payment_engine.model.enums.OutboxStatus;
import jakarta.persistence.*;

import java.time.Instant;

/** Outbox Event Entity — JPA mapping for the outbox_events table.
 *
 * Each row represents one domain event that needs to be published to Kafka.
 * Events are written to this table in the SAME transaction as the payment change.
 *
 * LIFECYCLE:
 *   1. PaymentService saves a payment + inserts an OutboxEvent (same transaction)
 *   2. Poller reads PENDING events
 *   3. Poller publishes to Kafka
 *   4. Poller marks as PUBLISHED + sets publishedAt timestamp
 *
 * WHY NOT DELETE AFTER PUBLISHING?
 *   We keep published events for auditing and debugging.
 *   A cleanup job can remove old PUBLISHED events after a retention period (e.g., 7 days).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * UUID for this event — used by Kafka consumers for idempotency.
     * If a consumer receives the same eventId twice, it can skip the duplicate.
     */
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    /**
     * The event type: PAYMENT_CREATED, PAYMENT_CAPTURED, etc.
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * The type of aggregate (entity) this event belongs to.
     * Always "PAYMENT" for now, but extensible for future entities
     * (e.g., "WALLET", "MERCHANT").
     */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /**
     * The ID of the aggregate this event belongs to (e.g., paymentId = 42).
     * Combined with aggregateType, uniquely identifies the entity.
     */
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    /**
     * The full event payload as a JSON string.
     * Contains everything a Kafka consumer needs to process the event.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * Publishing status: PENDING → PUBLISHED (or FAILED).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    /**
     * When this outbox event was created (same time as the payment transaction).
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When this event was successfully published to Kafka.
     * NULL until published.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    // ═══════════════════════════════════════════════
    // CONSTRUCTORS
    // ═══════════════════════════════════════════════

    /**
     * JPA requires a no-arg constructor.
     */
    protected OutboxEvent() {}

    /**
     * Create a new outbox event ready for publishing.
     *
     * @param eventId       UUID for consumer idempotency
     * @param eventType     Event type name (e.g., "PAYMENT_CAPTURED")
     * @param aggregateType Entity type (e.g., "PAYMENT")
     * @param aggregateId   Entity ID (e.g., paymentId = 42)
     * @param payload       JSON string of the full event data
     */
    public OutboxEvent(String eventId, String eventType, String aggregateType,
                       Long aggregateId, String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = Instant.now();
        this.publishedAt = null;
    }

    // ═══════════════════════════════════════════════
    // STATUS TRANSITION METHODS
    // ═══════════════════════════════════════════════

    /**
     * Mark this event as successfully published to Kafka.
     */
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /**
     * Mark this event as failed to publish.
     */
    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }

    // ═══════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public Long getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    @Override
    public String toString() {
        return "OutboxEvent{" +
                "id=" + id +
                ", eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", aggregateId=" + aggregateId +
                ", status=" + status +
                ", createdAt=" + createdAt +
                '}';
    }
}
