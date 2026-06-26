package com.distributed.payment_engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 52: Redis-backed Idempotency Service with Configurable TTL.
 *
 * This service provides fast, in-memory duplicate detection for payment creation.
 * Instead of hitting PostgreSQL for every idempotency check (slow disk I/O),
 * we check Redis first (sub-millisecond, in-memory).
 *
 * THE PATTERN (same as Stripe):
 * ┌─────────────────────────────────────────────────────────┐
 * │ 1. SET idempotency:{key} {paymentId} NX EX {ttl}       │
 * │    NX = only set if Not eXists (atomic dedup)           │
 * │    EX = expire after configured seconds                 │
 * │                                                         │
 * │ 2. If SET returns TRUE  → key is NEW, proceed           │
 * │    If SET returns FALSE → key EXISTS, it's a duplicate  │
 * └─────────────────────────────────────────────────────────┘
 *
 * ENHANCEMENT: TTL is now configurable via application.properties:
 *   payment.idempotency.ttl-seconds=300
 *
 * This allows different environments to use different retention windows:
 *   - Development:  300 seconds (5 min) — fast iteration
 *   - Staging:      3600 seconds (1 hour) — closer to prod
 *   - Production:   86400 seconds (24 hours) — what Stripe uses
 *
 * KEY FORMAT: "idempotency:{key}"
 * Example: "idempotency:txn-abc-123" → "42" (payment ID)
 */
@Service
public class RedisIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyService.class);

    /**
     * Redis key prefix for idempotency entries.
     * Using a prefix keeps keys organized and avoids collisions
     * with other Redis data (processing markers, locks, etc.).
     */
    private static final String KEY_PREFIX = "idempotency:";

    /** Configurable TTL from application.properties.
     *
     * How long to remember an idempotency key (in seconds).
     * After this time, the key expires and the idempotency key can be reused.
     * The PostgreSQL UNIQUE constraint remains as the permanent safety net.
     *
     * Configured via: payment.idempotency.ttl-seconds=300 (default: 300)
     */
    private final Duration ttl;

    private final StringRedisTemplate redisTemplate;

    public RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${payment.idempotency.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        log.info("[Idempotency] Initialized with TTL = {} seconds ({} minutes)",
                ttlSeconds, ttlSeconds / 60);
    }

    /**
     * Attempt to claim an idempotency key in Redis.
     *
     * Uses SET NX EX (atomic set-if-not-exists with expiration).
     * This is a single Redis command — no race conditions possible.
     *
     * @param idempotencyKey The client-provided idempotency key
     * @param paymentId      The payment ID to associate with this key
     * @return true if the key was claimed (new request), false if it already existed (duplicate)
     */
    public boolean claimKey(String idempotencyKey, Long paymentId) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        String value = String.valueOf(paymentId);

        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, value, ttl);

        if (Boolean.TRUE.equals(claimed)) {
            log.info("[Idempotency] Claimed key in Redis: {} → paymentId={} (TTL={}s)",
                    redisKey, paymentId, ttl.getSeconds());
            return true;
        } else {
            log.info("[Idempotency] Duplicate detected in Redis: {} (existing paymentId={})",
                    redisKey, getExistingPaymentId(idempotencyKey));
            return false;
        }
    }

    /**
     * Check if an idempotency key already exists in Redis.
     *
     * @param idempotencyKey The client-provided idempotency key
     * @return true if the key exists (duplicate), false if it doesn't (new)
     */
    public boolean exists(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
    }

    /**
     * Get the payment ID associated with an existing idempotency key.
     *
     * @param idempotencyKey The client-provided idempotency key
     * @return The payment ID, or null if the key doesn't exist
     */
    public Long getExistingPaymentId(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                log.warn("[Idempotency] Invalid payment ID in Redis for key {}: {}", redisKey, value);
                return null;
            }
        }
        return null;
    }

    /** Get the remaining TTL (time-to-live) for an idempotency key.
     *
     * Returns how many seconds remain before this key expires.
     * Useful for:
     *   - Debugging ("when will this key become available again?")
     *   - API responses ("retry after X seconds")
     *   - Monitoring dashboards
     *
     * @param idempotencyKey The client-provided idempotency key
     * @return Remaining TTL in seconds, or -2 if key doesn't exist, -1 if no TTL set
     */
    public long getRemainingTtl(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Long ttlRemaining = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        return ttlRemaining != null ? ttlRemaining : -2;
    }

    /** Refresh (extend) the TTL of an existing idempotency key.
     *
     * Resets the expiration timer back to the full configured TTL.
     * Useful when a payment is still being actively processed —
     * we don't want the key to expire mid-processing.
     *
     * Real-world example: A payment takes 4 minutes to process via
     * an external gateway. The 5-minute TTL would expire during processing.
     * Refreshing the TTL prevents a duplicate from sneaking through.
     *
     * @param idempotencyKey The client-provided idempotency key
     * @return true if the TTL was successfully refreshed, false if the key doesn't exist
     */
    public boolean refreshTtl(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean refreshed = redisTemplate.expire(redisKey, ttl);

        if (Boolean.TRUE.equals(refreshed)) {
            log.info("[Idempotency] Refreshed TTL for key: {} (new TTL={}s)", redisKey, ttl.getSeconds());
            return true;
        } else {
            log.warn("[Idempotency] Cannot refresh TTL — key does not exist: {}", redisKey);
            return false;
        }
    }

    /**
     * Remove an idempotency key from Redis.
     * Used when a payment creation fails and we need to allow retry.
     *
     * @param idempotencyKey The client-provided idempotency key
     */
    public void releaseKey(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        redisTemplate.delete(redisKey);
        log.info("[Idempotency] Released key from Redis: {}", redisKey);
    }

    /** Get the configured TTL duration.
     * Exposed for testing and monitoring.
     *
     * @return The TTL duration
     */
    public Duration getConfiguredTtl() {
        return ttl;
    }
}
