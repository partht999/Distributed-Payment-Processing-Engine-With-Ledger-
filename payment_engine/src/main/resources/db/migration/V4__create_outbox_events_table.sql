-- ═══════════════════════════════════════════════════════════════
-- V4: OUTBOX EVENTS TABLE 
-- ═══════════════════════════════════════════════════════════════
--
-- THE DUAL-WRITE PROBLEM:
--   When we save a payment AND publish an event, two things can go wrong:
--
--   Scenario 1: DB saves, Kafka publish fails → Event lost forever
--   Scenario 2: Kafka publishes, DB save fails → Phantom event (never happened)
--
-- THE SOLUTION:
--   Instead of publishing directly to Kafka, we INSERT the event into
--   this outbox_events table in the SAME database transaction as the payment.
--   PostgreSQL guarantees that either BOTH succeed or BOTH fail (ACID).
--
--   A separate poller reads from this table and publishes to Kafka.
--   After successful publish, it marks the row as PUBLISHED.
--
-- FLOW:
--   1. BEGIN TRANSACTION
--   2.   INSERT INTO payments (...)           ← payment saved
--   3.   INSERT INTO outbox_events (...)      ← event saved (same transaction!)
--   4. COMMIT                                 ← both succeed atomically
--   5. Poller reads PENDING events → publishes to Kafka → marks PUBLISHED
--
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE outbox_events (
    -- Unique identifier for this outbox entry
    id              BIGSERIAL       PRIMARY KEY,

    -- UUID for this event (used by Kafka consumers for idempotency)
    event_id        VARCHAR(36)     NOT NULL UNIQUE,

    -- The event type (PAYMENT_CREATED, PAYMENT_CAPTURED, etc.)
    event_type      VARCHAR(50)     NOT NULL,

    -- The aggregate (entity) this event belongs to
    aggregate_type  VARCHAR(50)     NOT NULL DEFAULT 'PAYMENT',

    -- The ID of the aggregate (e.g., payment_id = 42)
    aggregate_id    BIGINT          NOT NULL,

    -- The full event payload as JSON
    -- Contains all the data a Kafka consumer needs
    payload         TEXT            NOT NULL,

    -- Publishing status: PENDING → PUBLISHED (or FAILED)
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- When this event was created (inserted into outbox)
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- When this event was published to Kafka (NULL until published)
    published_at    TIMESTAMP       NULL
);

-- ═══ INDEX: Find unpublished events quickly ═══
-- The poller runs: SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at
-- Without this index, every poll scans the entire table.
CREATE INDEX idx_outbox_status_created
    ON outbox_events (status, created_at);

-- ═══ INDEX: Find events by aggregate (payment) ═══
-- Useful for debugging: "Show me all events for Payment #42"
CREATE INDEX idx_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);
