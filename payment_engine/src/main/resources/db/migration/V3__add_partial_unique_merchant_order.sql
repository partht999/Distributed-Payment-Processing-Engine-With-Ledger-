-- V3__add_partial_unique_merchant_order.sql
-- Database-level uniqueness for merchant order protection

-- ============================================================
-- PARTIAL UNIQUE INDEX on merchant_order_id
-- ============================================================
-- WHY a PARTIAL index instead of a simple UNIQUE constraint?
--
-- Business Rule: A merchant CAN retry with the same order ID
-- if the previous payment FAILED or EXPIRED. But they should NOT
-- be able to create a duplicate if a payment is still active
-- (CREATED, AUTHORIZED, CAPTURED, RETRYING).
--
-- A PostgreSQL "partial unique index" enforces uniqueness ONLY
-- for rows matching a WHERE condition. This is impossible with
-- a standard UNIQUE constraint — only PostgreSQL supports this.
--
-- Result: Two rows with merchant_order_id = 'ORDER-123' are allowed
-- if one has status = 'FAILED'. But two rows with 'ORDER-123'
-- where BOTH are 'CREATED' will be REJECTED by the database.

CREATE UNIQUE INDEX uq_payments_active_merchant_order
    ON payments (merchant_order_id)
    WHERE status IN ('CREATED', 'AUTHORIZED', 'CAPTURED', 'RETRYING');
