package com.distributed.payment_engine.repository;

import com.distributed.payment_engine.model.entity.OutboxEvent;
import com.distributed.payment_engine.model.enums.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Outbox Event Repository.
 *
 * Provides database access for the outbox_events table.
 * The key query is findByStatusOrderByCreatedAtAsc — used by the poller * to pick up events that need publishing, in the order they were created.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Find all events with the given status, ordered by creation time (oldest first).
     * Used by the poller to publish PENDING events in order.
     *
     * Example: findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING)
     * → Returns all unpublished events, oldest first.
     */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /**
     * Find all events for a specific aggregate (e.g., all events for Payment #42).
     * Useful for debugging and audit trails.
     *
     * Example: findByAggregateTypeAndAggregateId("PAYMENT", 42L)
     * → Returns [PAYMENT_CREATED, PAYMENT_CAPTURED] for Payment #42.
     */
    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, Long aggregateId);

    /**
     * Find an event by its UUID.
     * Useful for idempotency checks — "was this event already saved?"
     */
    OutboxEvent findByEventId(String eventId);
}
