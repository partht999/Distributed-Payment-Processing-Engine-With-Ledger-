package com.distributed.payment_engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis Processing Marker Service.
 *
 * THE PROBLEM THIS SOLVES (different from IdempotencyService and DistributedLockService):
 *
 * IdempotencyService  → Prevents duplicate CREATION of a payment
 * DistributedLock     → Prevents two payments from the same WALLET at once
 * This service        → Prevents the same PAYMENT from being processed twice
 *
 * SCENARIO WITHOUT THIS:
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 1. PaymentRetryScheduler picks up Payment #42 (RETRYING)    │
 * │ 2. At the same instant, admin clicks "retry" on Payment #42 │
 * │ 3. Two threads now both call processPayment(42)             │
 * │ 4. Both pass the wallet lock (different wallets? or tiny gap)│
 * │ 5. Both debit the sender → DOUBLE CHARGE!                   │
 * └──────────────────────────────────────────────────────────────┘
 *
 * THE SOLUTION:
 * Before processing, set a "processing marker" in Redis:
 *   SET processing:payment:{id} {serverInfo} NX EX 60
 *
 * If the marker already exists → another thread/server is already
 * processing this exact payment → reject immediately.
 *
 * KEY FORMAT: "processing:payment:{paymentId}"
 * VALUE: Server identity + timestamp (for debugging)
 * TTL: 60 seconds (auto-cleanup if server crashes mid-processing)
 */
@Service
public class RedisProcessingMarkerService {

    private static final Logger log = LoggerFactory.getLogger(RedisProcessingMarkerService.class);

    private static final String KEY_PREFIX = "processing:payment:";

    private final StringRedisTemplate redisTemplate;
    private final Duration markerTtl;

    public RedisProcessingMarkerService(
            StringRedisTemplate redisTemplate,
            @Value("${payment.processing-marker.ttl-seconds:60}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.markerTtl = Duration.ofSeconds(ttlSeconds);
        log.info("[ProcessingMarker] Initialized with TTL = {} seconds", ttlSeconds);
    }

    /**
     * Attempt to mark a payment as "currently being processed".
     *
     * Uses SET NX EX — only one thread/server can mark a payment at a time.
     * The value contains server info for debugging which server is processing it.
     *
     * @param paymentId The payment being processed
     * @return true if the marker was set (this thread should proceed),
     *         false if the marker already exists (another thread is processing it)
     */
    public boolean markProcessing(Long paymentId) {
        String key = KEY_PREFIX + paymentId;
        String value = buildMarkerValue();

        Boolean marked = redisTemplate.opsForValue()
                .setIfAbsent(key, value, markerTtl);

        if (Boolean.TRUE.equals(marked)) {
            log.info("[ProcessingMarker] Marked payment {} as processing (TTL={}s)",
                    paymentId, markerTtl.getSeconds());
            return true;
        } else {
            String existingMarker = redisTemplate.opsForValue().get(key);
            log.warn("[ProcessingMarker] Payment {} is already being processed by: {}",
                    paymentId, existingMarker);
            return false;
        }
    }

    /**
     * Remove the processing marker after payment processing completes.
     * Called in a finally block to ensure cleanup.
     *
     * @param paymentId The payment that finished processing
     */
    public void clearMarker(Long paymentId) {
        String key = KEY_PREFIX + paymentId;
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("[ProcessingMarker] Cleared processing marker for payment {}", paymentId);
        }
    }

    /**
     * Check if a payment is currently being processed.
     * Useful for API responses: "Payment is currently being processed, please wait."
     *
     * @param paymentId The payment to check
     * @return true if the payment has an active processing marker
     */
    public boolean isProcessing(Long paymentId) {
        String key = KEY_PREFIX + paymentId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * Get details about who is processing a payment.
     * Returns the marker value (server info + timestamp) or null.
     *
     * @param paymentId The payment to check
     * @return Marker details string, or null if not being processed
     */
    public String getProcessingDetails(Long paymentId) {
        String key = KEY_PREFIX + paymentId;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Get the configured marker TTL.
     * Exposed for testing.
     */
    public Duration getConfiguredTtl() {
        return markerTtl;
    }

    /**
     * Build a descriptive marker value for debugging.
     * Contains thread name and timestamp so you can identify
     * which server/thread is processing the payment.
     */
    private String buildMarkerValue() {
        return Thread.currentThread().getName() + "@" + Instant.now().toString();
    }
}
