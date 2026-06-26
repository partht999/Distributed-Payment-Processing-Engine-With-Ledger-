package com.distributed.payment_engine;

import com.distributed.payment_engine.service.RedisIdempotencyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Idempotency TTL Integration Test.
 *
 * Tests the complete TTL lifecycle:
 *   1. Key claimed with correct TTL
 *   2. TTL is counting down
 *   3. TTL can be refreshed (extended)
 *   4. Key auto-expires after TTL
 *   5. Expired key allows re-claim
 *
 * PREREQUISITES:
 *   docker compose up -d   (starts PostgreSQL + Redis)
 *
 * RUN:
 *   .\mvnw.cmd test -Dtest=IdempotencyTtlTest
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IdempotencyTtlTest {

    @Autowired
    private RedisIdempotencyService idempotencyService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_KEY = "ttl-test-key-day52";
    private static final String SHORT_TTL_KEY = "ttl-short-expiry-day52";

    @AfterAll
    void cleanup() {
        // Clean up all test keys
        stringRedisTemplate.delete("idempotency:" + TEST_KEY);
        stringRedisTemplate.delete("idempotency:" + SHORT_TTL_KEY);
    }

    // ═══════════════════════════════════════════════
    // TEST 1: Verify configured TTL value from properties
    // ═══════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1. TTL is configured from application.properties (300 seconds)")
    void testTtlConfiguration() {
        Duration configuredTtl = idempotencyService.getConfiguredTtl();
        assertEquals(300, configuredTtl.getSeconds(),
                "TTL should be 300 seconds (from payment.idempotency.ttl-seconds)");
    }

    // ═══════════════════════════════════════════════
    // TEST 2: Claimed key has correct TTL set
    // ═══════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("2. Claimed key has TTL set in Redis")
    void testClaimSetsCorrectTtl() {
        // Claim a key
        boolean claimed = idempotencyService.claimKey(TEST_KEY, 1001L);
        assertTrue(claimed, "Should claim a new key");

        // Check TTL — should be close to 300 seconds
        long remainingTtl = idempotencyService.getRemainingTtl(TEST_KEY);
        assertTrue(remainingTtl > 295 && remainingTtl <= 300,
                "TTL should be between 295-300 seconds, was: " + remainingTtl);
    }

    // ═══════════════════════════════════════════════
    // TEST 3: TTL is actively counting down
    // ═══════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("3. TTL counts down over time")
    void testTtlCountsDown() throws InterruptedException {
        // Wait 2 seconds
        Thread.sleep(2000);

        // TTL should have decreased
        long remainingTtl = idempotencyService.getRemainingTtl(TEST_KEY);
        assertTrue(remainingTtl < 299,
                "TTL should have decreased after 2 seconds, was: " + remainingTtl);
        assertTrue(remainingTtl > 290,
                "TTL shouldn't have decreased too much, was: " + remainingTtl);
    }

    // ═══════════════════════════════════════════════
    // TEST 4: Refresh TTL resets the timer
    // ═══════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("4. refreshTtl() resets the expiration timer back to full duration")
    void testRefreshTtl() {
        // Refresh the TTL
        boolean refreshed = idempotencyService.refreshTtl(TEST_KEY);
        assertTrue(refreshed, "Should refresh TTL for existing key");

        // TTL should be back to ~300 seconds
        long remainingTtl = idempotencyService.getRemainingTtl(TEST_KEY);
        assertTrue(remainingTtl > 295 && remainingTtl <= 300,
                "TTL should be refreshed back to ~300 seconds, was: " + remainingTtl);
    }

    // ═══════════════════════════════════════════════
    // TEST 5: Refresh on non-existent key returns false
    // ═══════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("5. refreshTtl() returns false for non-existent key")
    void testRefreshNonExistentKey() {
        boolean refreshed = idempotencyService.refreshTtl("does-not-exist-key");
        assertFalse(refreshed, "Should not refresh a key that doesn't exist");
    }

    // ═══════════════════════════════════════════════
    // TEST 6: Key auto-expires after TTL
    // ═══════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("6. Key automatically expires and can be re-claimed")
    void testKeyAutoExpires() throws InterruptedException {
        // Set a key with a very short TTL directly in Redis (2 seconds)
        String redisKey = "idempotency:" + SHORT_TTL_KEY;
        stringRedisTemplate.opsForValue()
                .set(redisKey, "9999", Duration.ofSeconds(2));

        // Key should exist
        assertTrue(idempotencyService.exists(SHORT_TTL_KEY),
                "Key should exist immediately after creation");

        // Get remaining TTL
        long ttl = idempotencyService.getRemainingTtl(SHORT_TTL_KEY);
        assertTrue(ttl > 0, "TTL should be positive, was: " + ttl);

        // Wait for expiry
        Thread.sleep(2500);

        // Key should be gone
        assertFalse(idempotencyService.exists(SHORT_TTL_KEY),
                "Key should have expired after TTL");

        // getRemainingTtl should return -2 (key doesn't exist)
        long ttlAfter = idempotencyService.getRemainingTtl(SHORT_TTL_KEY);
        assertEquals(-2, ttlAfter,
                "getRemainingTtl should return -2 for expired/non-existent key");

        // Should be able to re-claim the same key
        boolean reClaimed = idempotencyService.claimKey(SHORT_TTL_KEY, 2002L);
        assertTrue(reClaimed,
                "Should be able to re-claim an expired key");

        // Verify the new value
        Long paymentId = idempotencyService.getExistingPaymentId(SHORT_TTL_KEY);
        assertEquals(2002L, paymentId,
                "Re-claimed key should have the new payment ID");
    }

    // ═══════════════════════════════════════════════
    // TEST 7: Duplicate claim within TTL is rejected
    // ═══════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("7. Duplicate claim within TTL window is rejected")
    void testDuplicateWithinTtl() {
        // The SHORT_TTL_KEY was re-claimed in test 6 with paymentId 2002
        // Try to claim again with different paymentId
        boolean duplicate = idempotencyService.claimKey(SHORT_TTL_KEY, 3003L);
        assertFalse(duplicate, "Should reject duplicate claim within TTL window");

        // Original value should be preserved
        Long paymentId = idempotencyService.getExistingPaymentId(SHORT_TTL_KEY);
        assertEquals(2002L, paymentId,
                "Original payment ID should be preserved, not overwritten");
    }
}
