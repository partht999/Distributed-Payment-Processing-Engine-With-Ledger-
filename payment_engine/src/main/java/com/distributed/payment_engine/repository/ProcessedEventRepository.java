package com.distributed.payment_engine.repository;

import com.distributed.payment_engine.model.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Processed Event Repository.
 *
 * Provides database access for the processed_events table to check if an event has already been handled.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * Check if a specific eventId has already been processed.
     */
    boolean existsByEventId(String eventId);

    /**
     * Find a processed event by its eventId.
     */
    Optional<ProcessedEvent> findByEventId(String eventId);
}
