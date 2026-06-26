package com.distributed.payment_engine.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Processed Event Entity — tracks processed Kafka events to enforce idempotency.
 *
 * Kafka guarantees at-least-once delivery, meaning consumers must handle duplicates.
 * We store the eventId of successfully processed events in the database.
 * If the same eventId is received again, the consumer skips processing.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /**
     * JPA no-arg constructor.
     */
    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    @Override
    public String toString() {
        return "ProcessedEvent{" +
                "id=" + id +
                ", eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", processedAt=" + processedAt +
                '}';
    }
}
