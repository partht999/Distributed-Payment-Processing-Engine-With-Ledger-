package com.distributed.payment_engine;

import com.distributed.payment_engine.service.RedisProcessingMarkerService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Processing Marker Integration Test.
 *
 * Tests the complete processing marker lifecycle:
 *   1. Mark a payment as processing
 *   2. Duplicate processing is rejected
 *   3. Clear marker allows re-processing
 *   4. Marker auto-expires (prevents stuck payments)
 *   5. Different payments can be processed independently
 *
 * PREREQUISITES:
 *   docker compose up -d   (starts PostgreSQL + Redis)
 *
 * RUN:
 *   .\mvnw.cmd test -Dtest=ProcessingMarkerTest
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProcessingMarkerTest {

    @Autowired
    private RedisProcessingMarkerService markerService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long PAYMENT_A = 88001L;
    private static final Long PAYMENT_B = 88002L;
    private static final Long PAYMENT_SHORT_TTL = 88003L;

    @AfterAll
    void cleanup() {
        stringRedisTemplate.delete("processing:payment:" + PAYMENT_A);
        stringRedisTemplate.delete("processing:payment:" + PAYMENT_B);
        stringRedisTemplate.delete("processing:payment:" + PAYMENT_SHORT_TTL);
    }

    // ═══════════════════════════════════════════════
    // TEST 1: Marker TTL is configured from properties
    // ═══════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1. Marker TTL is configured from application.properties (60 seconds)")
    void testMarkerTtlConfiguration() {
        Duration configuredTtl = markerService.getConfiguredTtl();
        assertEquals(60, configuredTtl.getSeconds(),
                "Marker TTL should be 60 seconds (from payment.processing-marker.ttl-seconds)");
    }

    // ═══════════════════════════════════════════════
    // TEST 2: Successfully mark a payment as processing
    // ═══════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("2. Successfully mark a payment as processing")
    void testMarkProcessing() {
        boolean marked = markerService.markProcessing(PAYMENT_A);
        assertTrue(marked, "Should successfully mark payment as processing");
        assertTrue(markerService.isProcessing(PAYMENT_A), "Payment should be marked");

        // Clean up
        markerService.clearMarker(PAYMENT_A);
    }

    // ═══════════════════════════════════════════════
    // TEST 3: Duplicate processing is rejected
    // ═══════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("3. Second mark attempt on same payment is rejected")
    void testDuplicateProcessingRejected() {
        // Thread A marks the payment
        boolean firstMark = markerService.markProcessing(PAYMENT_A);
        assertTrue(firstMark, "First mark should succeed");

        // Thread B tries to mark the same payment
        boolean secondMark = markerService.markProcessing(PAYMENT_A);
        assertFalse(secondMark, "Second mark should be REJECTED — payment already processing");

        // Clean up
        markerService.clearMarker(PAYMENT_A);
    }

    // ═══════════════════════════════════════════════
    // TEST 4: Different payments can be processed independently
    // ═══════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("4. Different payments can be marked simultaneously")
    void testIndependentPayments() {
        boolean markA = markerService.markProcessing(PAYMENT_A);
        boolean markB = markerService.markProcessing(PAYMENT_B);

        assertTrue(markA, "Payment A should be marked");
        assertTrue(markB, "Payment B should be marked — independent payment");

        assertTrue(markerService.isProcessing(PAYMENT_A));
        assertTrue(markerService.isProcessing(PAYMENT_B));

        markerService.clearMarker(PAYMENT_A);
        markerService.clearMarker(PAYMENT_B);
    }

    // ═══════════════════════════════════════════════
    // TEST 5: Clear marker allows re-processing
    // ═══════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("5. Clearing marker allows re-processing")
    void testClearAndReprocess() {
        // Mark
        assertTrue(markerService.markProcessing(PAYMENT_A));
        assertTrue(markerService.isProcessing(PAYMENT_A));

        // Clear
        markerService.clearMarker(PAYMENT_A);
        assertFalse(markerService.isProcessing(PAYMENT_A), "Payment should not be processing after clear");

        // Re-mark (should succeed)
        assertTrue(markerService.markProcessing(PAYMENT_A),
                "Should be able to re-mark after clearing");

        markerService.clearMarker(PAYMENT_A);
    }

    // ═══════════════════════════════════════════════
    // TEST 6: Marker contains server/thread info
    // ═══════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("6. Marker value contains thread info for debugging")
    void testMarkerContainsDebugInfo() {
        markerService.markProcessing(PAYMENT_A);

        String details = markerService.getProcessingDetails(PAYMENT_A);
        assertNotNull(details, "Processing details should not be null");
        assertTrue(details.contains("@"), "Details should contain thread@timestamp format");

        markerService.clearMarker(PAYMENT_A);
    }

    // ═══════════════════════════════════════════════
    // TEST 7: Non-processing payment returns null details
    // ═══════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("7. Non-processing payment returns null details")
    void testNoDetailsForUnmarkedPayment() {
        String details = markerService.getProcessingDetails(99999L);
        assertNull(details, "Unmarked payment should have null details");
        assertFalse(markerService.isProcessing(99999L));
    }

    // ═══════════════════════════════════════════════
    // TEST 8: Marker auto-expires (prevents stuck payments)
    // ═══════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("8. Marker auto-expires after TTL (prevents stuck payments)")
    void testMarkerAutoExpiry() throws InterruptedException {
        // Set a marker with very short TTL directly in Redis (2 seconds)
        String key = "processing:payment:" + PAYMENT_SHORT_TTL;
        stringRedisTemplate.opsForValue()
                .set(key, "crashed-server@2026-01-01T00:00:00Z", Duration.ofSeconds(2));

        // Payment should be marked
        assertTrue(markerService.isProcessing(PAYMENT_SHORT_TTL));

        // Wait for expiry
        Thread.sleep(2500);

        // Marker should be gone — payment can be retried
        assertFalse(markerService.isProcessing(PAYMENT_SHORT_TTL),
                "Marker should have auto-expired — stuck payment freed");

        // Re-mark should succeed
        boolean reMark = markerService.markProcessing(PAYMENT_SHORT_TTL);
        assertTrue(reMark, "Should be able to mark after auto-expiry");

        markerService.clearMarker(PAYMENT_SHORT_TTL);
    }
}
