package com.distributed.payment_engine.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

/** Webhook Configuration Entity.
 *
 * Stores the webhook URL that a merchant has registered.
 * When a payment event occurs for this merchant's wallet,
 * we POST the event JSON to this URL.
 *
 * EXAMPLE:
 *   Merchant "ShopABC" registers: https://shopABC.com/webhooks/payments
 *   When a payment to wallet #5 (ShopABC's wallet) is captured,
 *   we POST the PAYMENT_CAPTURED event to their webhook URL.
 *
 * IN PRODUCTION:
 *   - Merchants register webhooks via an admin API
 *   - Each merchant can have multiple webhook URLs
 *   - We store a secret for HMAC signature verification
 *   - We track delivery status (delivered, failed, retrying)
 *
 * FOR LEARNING:
 *   We keep it simple — one webhook URL per wallet.
 */
@Entity
@Table(name = "webhook_configs")
public class WebhookConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The wallet ID this webhook is registered for.
     * Events involving this wallet will be sent to the webhook URL.
     */
    @Column(name = "wallet_id", nullable = false, unique = true)
    private Long walletId;

    /**
     * The URL to POST events to.
     * Example: https://merchant.com/webhooks/payments
     */
    @Column(name = "webhook_url", nullable = false)
    private String webhookUrl;

    /**
     * A secret key used to sign webhook payloads (HMAC-SHA256).
     * The merchant uses this to verify the webhook came from us
     * and wasn't tampered with.
     */
    @Column(name = "secret", nullable = false)
    private String secret;

    /**
     * Whether this webhook is active.
     * Disabled webhooks are not called.
     */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookConfig() {}

    public WebhookConfig(Long walletId, String webhookUrl, String secret) {
        this.walletId = walletId;
        this.webhookUrl = webhookUrl;
        this.secret = secret;
        this.active = true;
        this.createdAt = Instant.now();
    }

    // ═══ Getters ═══
    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getSecret() { return secret; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void setActive(boolean active) { this.active = active; }

    @Override
    public String toString() {
        return "WebhookConfig{" +
                "id=" + id +
                ", walletId=" + walletId +
                ", webhookUrl='" + webhookUrl + '\'' +
                ", active=" + active +
                '}';
    }
}
