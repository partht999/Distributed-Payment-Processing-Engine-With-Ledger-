package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.WebhookConfig;
import com.distributed.payment_engine.repository.WebhookConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/** Webhook Dispatch Service.
 *
 * When a payment event arrives from Kafka, this service checks:
 *   1. Does the target wallet (toWalletId) have a registered webhook?
 *   2. If yes, POST the event JSON to the merchant's webhook URL
 *   3. Sign the payload with HMAC-SHA256 so the merchant can verify authenticity
 *
 * WEBHOOK FLOW:
 *   Kafka Consumer → receives event → calls WebhookService.dispatch()
 *   → lookup webhook_configs for the wallet
 *   → compute HMAC-SHA256 signature
 *   → POST JSON to merchant's URL with X-Signature header
 *
 * SECURITY:
 *   The merchant stores the secret we gave them when they registered.
 *   They compute HMAC-SHA256(payload, secret) on their end and compare
 *   it to the X-Signature header. If they match, the webhook is authentic.
 *   This prevents attackers from sending fake webhooks.
 *
 * TIMEOUT:
 *   We give the merchant 5 seconds to respond. If they don't,
 *   we consider it a failure and log it. In production, we'd retry.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    @Autowired
    private WebhookConfigRepository webhookRepo;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Dispatch a payment event to the appropriate webhook, if one is registered.
     *
     * @param toWalletId  The receiving wallet (we look up webhooks by this)
     * @param eventJson   The full JSON payload of the event
     * @return true if webhook was called successfully or no webhook is registered,
     *         false if the webhook call failed
     */
    public boolean dispatch(Long toWalletId, String eventJson) {
        Optional<WebhookConfig> configOpt = webhookRepo.findByWalletIdAndActiveTrue(toWalletId);

        if (configOpt.isEmpty()) {
            // No webhook registered for this wallet — that's fine, not an error
            log.debug("[Webhook] No webhook registered for wallet {}", toWalletId);
            return true;
        }

        WebhookConfig config = configOpt.get();

        try {
            // Compute HMAC-SHA256 signature
            String signature = computeHmac(eventJson, config.getSecret());

            // Build the HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-Signature", signature)
                    .header("X-Event-Source", "payment-engine")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(eventJson))
                    .build();

            // Send it
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[Webhook] Successfully delivered to {} (wallet {}), status={}",
                        config.getWebhookUrl(), toWalletId, response.statusCode());
                return true;
            } else {
                log.warn("[Webhook] Delivery to {} failed with status {} (wallet {})",
                        config.getWebhookUrl(), response.statusCode(), toWalletId);
                return false;
            }

        } catch (Exception e) {
            log.error("[Webhook] Failed to deliver to {} (wallet {}). Error: {}",
                    config.getWebhookUrl(), toWalletId, e.getMessage());
            return false;
        }
    }

    /**
     * Compute HMAC-SHA256 signature for the payload.
     *
     * The merchant uses the same algorithm with their stored secret
     * to verify that the webhook came from us and wasn't tampered with.
     *
     * @param payload The JSON body
     * @param secret  The shared secret
     * @return Hex-encoded HMAC-SHA256 signature
     */
    private String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
