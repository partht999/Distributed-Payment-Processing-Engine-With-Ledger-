-- ═══════════════════════════════════════════════════════════════
-- V5: WEBHOOK CONFIGS TABLE 
-- ═══════════════════════════════════════════════════════════════
--
-- Stores merchant webhook registrations.
-- When a payment event occurs involving a wallet that has a webhook,
-- the system POSTs the event payload to the registered URL.

CREATE TABLE webhook_configs (
    id              BIGSERIAL       PRIMARY KEY,
    wallet_id       BIGINT          NOT NULL UNIQUE,
    webhook_url     VARCHAR(500)    NOT NULL,
    secret          VARCHAR(128)    NOT NULL,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_wallet ON webhook_configs (wallet_id);
