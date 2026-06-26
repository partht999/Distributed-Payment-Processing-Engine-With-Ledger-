-- V2__add_unique_constraints_and_indexes.sql
-- Database-level uniqueness for idempotency and performance indexes

-- ============================================================
-- 1. UNIQUE CONSTRAINT on idempotency_key (payments table)
-- ============================================================
-- WHY: Our Java code checks for duplicates BEFORE inserting:
--   Payment existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
--   if (existing != null) return existing;
-- But this is NOT safe under concurrency. If two identical requests
-- arrive at the exact same millisecond, BOTH threads could read
-- "no existing payment" and BOTH could insert — creating duplicates.
-- A UNIQUE constraint makes the DATABASE itself reject the second insert.
-- This is called "storage-level duplicate prevention".

ALTER TABLE payments
    ADD CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key);

-- ============================================================
-- 2. INDEX on merchant_order_id (payments table)
-- ============================================================
-- WHY: We query by merchant_order_id for business duplicate detection.
-- Without an index, PostgreSQL does a full table scan every time.
-- An index turns this from O(n) to O(log n).
-- We do NOT make this UNIQUE because the same merchant_order_id
-- is allowed if the previous payment FAILED or EXPIRED.

CREATE INDEX idx_payments_merchant_order_id
    ON payments (merchant_order_id);

-- ============================================================
-- 3. INDEX on wallet_id in ledger_entries
-- ============================================================
-- WHY: When we calculate a wallet's ledger-derived balance,
-- we query: SELECT * FROM ledger_entries WHERE wallet_id = ?
-- Without an index, this is a full table scan every single time.

CREATE INDEX idx_ledger_entries_wallet_id
    ON ledger_entries (wallet_id);

-- ============================================================
-- 4. INDEX on payment_id in ledger_entries
-- ============================================================
-- WHY: When we trace which ledger entries belong to a specific
-- payment for audit/reconciliation purposes.

CREATE INDEX idx_ledger_entries_payment_id
    ON ledger_entries (payment_id);

-- ============================================================
-- 5. INDEX on payment_id in transactions
-- ============================================================
-- WHY: Every transaction is linked to a payment.
-- Fast lookup by payment_id is critical for traceability.

CREATE INDEX idx_transactions_payment_id
    ON transactions (payment_id);

-- ============================================================
-- 6. INDEX on from_wallet_id and to_wallet_id in transactions
-- ============================================================
-- WHY: When querying transaction history for a specific wallet.

CREATE INDEX idx_transactions_from_wallet_id
    ON transactions (from_wallet_id);

CREATE INDEX idx_transactions_to_wallet_id
    ON transactions (to_wallet_id);
