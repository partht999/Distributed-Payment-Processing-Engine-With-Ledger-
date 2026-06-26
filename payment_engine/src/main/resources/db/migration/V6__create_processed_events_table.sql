-- ═══════════════════════════════════════════════════════════════
-- V6: PROCESSED EVENTS TABLE 
-- ═══════════════════════════════════════════════════════════════
--
-- Kafka guarantees "at-least-once" delivery. This means a consumer
-- might receive the same event TWICE (e.g., after a rebalance or crash).
--
-- This table tracks which events we have already processed.
-- Before processing an event, we check: "Is this eventId already here?"
--   - If YES → skip it (duplicate)
--   - If NO  → process it and INSERT into this table
--
-- The INSERT uses the UNIQUE constraint on event_id — even if two threads
-- try to process the same event simultaneously, only one will succeed.

CREATE TABLE processed_events (
    id              BIGSERIAL       PRIMARY KEY,
    event_id        VARCHAR(36)     NOT NULL UNIQUE,
    event_type      VARCHAR(50)     NOT NULL,
    processed_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_processed_event_id ON processed_events (event_id);
