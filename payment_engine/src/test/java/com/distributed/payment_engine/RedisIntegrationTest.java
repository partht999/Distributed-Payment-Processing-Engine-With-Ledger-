package com.distributed.payment_engine;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis Connectivity & Operations Integration Test.
 *
 * PREREQUISITES:
 *   docker compose up -d   (starts PostgreSQL + Redis)
 *
 * This test verifies that:
 * 1. Spring can connect to Redis
 * 2. Basic SET/GET operations work
 * 3. SET NX (set-if-not-exists) works — critical for idempotency
 * 4. TTL/expiration works — critical for key expiry
 * 5. RedisTemplate (JSON serialization) works for complex objects
 * 6. DELETE operation works for cleanup
 *
 * All test keys are cleaned up in @AfterAll.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedisIntegrationTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String TEST_KEY = "test:redis:connectivity";
    private static final String TEST_NX_KEY = "test:redis:setnx";
    private static final String TEST_TTL_KEY = "test:redis:ttl";
    private static final String TEST_JSON_KEY = "test:redis:json";

    @AfterAll
    void cleanup() {
        // Clean up all test keys
        stringRedisTemplate.delete(TEST_KEY);
        stringRedisTemplate.delete(TEST_NX_KEY);
        stringRedisTemplate.delete(TEST_TTL_KEY);
        redisTemplate.delete(TEST_JSON_KEY);
    }

    // ═══════════════════════════════════════════════
    // TEST 1: Basic Connectivity — PING
    // ═══════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1. Redis PING → PONG (connectivity check)")
    void testRedisPing() {
        String pong = stringRedisTemplate.getConnectionFactory()
                .getConnection().ping();
        assertEquals("PONG", pong, "Redis should respond with PONG");
    }

    // ═══════════════════════════════════════════════
    // TEST 2: SET and GET — basic string operations
    // ═══════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("2. SET/GET — write and read a string value")
    void testSetAndGet() {
        stringRedisTemplate.opsForValue().set(TEST_KEY, "payment_engine_alive");

        String value = stringRedisTemplate.opsForValue().get(TEST_KEY);
        assertEquals("payment_engine_alive", value,
                "Should read back the exact value that was written");
    }

    // ═══════════════════════════════════════════════
    // TEST 3: SET NX — set-if-not-exists (idempotency pattern)
    // ═══════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("3. SET NX — first write succeeds, second is rejected")
    void testSetNx() {
        // First SET NX — should succeed (key doesn't exist)
        Boolean firstWrite = stringRedisTemplate.opsForValue()
                .setIfAbsent(TEST_NX_KEY, "first-request");
        assertTrue(firstWrite, "First SET NX should succeed");

        // Second SET NX — should FAIL (key already exists)
        Boolean secondWrite = stringRedisTemplate.opsForValue()
                .setIfAbsent(TEST_NX_KEY, "duplicate-request");
        assertFalse(secondWrite, "Second SET NX should fail — key exists");

        // Value should still be from first write
        String value = stringRedisTemplate.opsForValue().get(TEST_NX_KEY);
        assertEquals("first-request", value,
                "Value should be from the first write, not the duplicate");
    }

    // ═══════════════════════════════════════════════
    // TEST 4: TTL — key expires after timeout
    // ═══════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("4. TTL — key expires after set duration")
    void testTtlExpiration() throws InterruptedException {
        // SET with 2 second TTL
        stringRedisTemplate.opsForValue()
                .set(TEST_TTL_KEY, "temporary-value", Duration.ofSeconds(2));

        // Immediately readable
        String valueBefore = stringRedisTemplate.opsForValue().get(TEST_TTL_KEY);
        assertEquals("temporary-value", valueBefore, "Key should exist immediately");

        // Wait for expiry
        Thread.sleep(2500);

        // Should be gone now
        String valueAfter = stringRedisTemplate.opsForValue().get(TEST_TTL_KEY);
        assertNull(valueAfter, "Key should have expired after TTL");
    }

    // ═══════════════════════════════════════════════
    // TEST 5: SET NX with EX — the full idempotency pattern
    // ═══════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("5. SET NX EX — idempotency key with expiration (Stripe pattern)")
    void testSetNxWithExpiry() {
        String idempotencyKey = "test:idempotency:txn-abc-123";

        try {
            // SET NX EX 300 — set if not exists, expire in 300 seconds
            Boolean result = stringRedisTemplate.opsForValue()
                    .setIfAbsent(idempotencyKey, "payment-42", Duration.ofSeconds(300));
            assertTrue(result, "First idempotency check should succeed");

            // Duplicate check — should be rejected
            Boolean duplicate = stringRedisTemplate.opsForValue()
                    .setIfAbsent(idempotencyKey, "payment-99", Duration.ofSeconds(300));
            assertFalse(duplicate, "Duplicate idempotency key should be rejected");

            // Original value preserved
            String stored = stringRedisTemplate.opsForValue().get(idempotencyKey);
            assertEquals("payment-42", stored,
                    "Original payment ID should be preserved");

            // TTL should be set
            Long ttl = stringRedisTemplate.getExpire(idempotencyKey, TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 300,
                    "TTL should be between 0 and 300 seconds, was: " + ttl);

        } finally {
            stringRedisTemplate.delete(idempotencyKey);
        }
    }

    // ═══════════════════════════════════════════════
    // TEST 6: RedisTemplate — JSON object serialization
    // ═══════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("6. RedisTemplate — store and retrieve a Java object as JSON")
    void testJsonSerialization() {
        // Store a map as a JSON value
        java.util.Map<String, Object> paymentData = new java.util.LinkedHashMap<>();
        paymentData.put("paymentId", 42L);
        paymentData.put("status", "CAPTURED");
        paymentData.put("amount", 5000L);

        redisTemplate.opsForValue().set(TEST_JSON_KEY, paymentData);

        // Read it back
        Object retrieved = redisTemplate.opsForValue().get(TEST_JSON_KEY);
        assertNotNull(retrieved, "Should retrieve the stored object");
        assertTrue(retrieved instanceof java.util.Map, "Should deserialize as a Map");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) retrieved;
        assertEquals(42, ((Number) result.get("paymentId")).intValue());
        assertEquals("CAPTURED", result.get("status"));
        assertEquals(5000, ((Number) result.get("amount")).intValue());
    }

    // ═══════════════════════════════════════════════
    // TEST 7: DELETE — cleanup works
    // ═══════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("7. DELETE — remove a key from Redis")
    void testDelete() {
        String deleteKey = "test:redis:delete-me";
        stringRedisTemplate.opsForValue().set(deleteKey, "bye");

        // Verify it exists
        assertNotNull(stringRedisTemplate.opsForValue().get(deleteKey));

        // Delete it
        Boolean deleted = stringRedisTemplate.delete(deleteKey);
        assertTrue(deleted, "Delete should return true for existing key");

        // Verify it's gone
        String afterDelete = stringRedisTemplate.opsForValue().get(deleteKey);
        assertNull(afterDelete, "Key should be null after deletion");
    }
}
