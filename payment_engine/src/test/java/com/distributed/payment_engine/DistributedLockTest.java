package com.distributed.payment_engine;

import com.distributed.payment_engine.service.RedisDistributedLockService;
import com.distributed.payment_engine.service.RedisDistributedLockService.LockHandle;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Distributed Lock Integration Test.
 *
 * Tests the complete distributed locking lifecycle:
 *   1. Lock can be acquired
 *   2. Double-lock is rejected (only one owner at a time)
 *   3. Lock can be released by the owner
 *   4. Released lock can be re-acquired
 *   5. Only the lock owner can release it (Lua script safety)
 *   6. Lock has correct TTL set
 *
 * PREREQUISITES:
 *   docker compose up -d   (starts PostgreSQL + Redis)
 *
 * RUN:
 *   .\mvnw.cmd test -Dtest=DistributedLockTest
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DistributedLockTest {

    @Autowired
    private RedisDistributedLockService lockService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long TEST_WALLET_ID = 99001L;
    private static final Long TEST_WALLET_ID_2 = 99002L;

    @AfterAll
    void cleanup() {
        stringRedisTemplate.delete("lock:wallet:" + TEST_WALLET_ID);
        stringRedisTemplate.delete("lock:wallet:" + TEST_WALLET_ID_2);
    }

    // ═══════════════════════════════════════════════
    // TEST 1: Lock TTL is configured from properties
    // ═══════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("1. Lock TTL is configured from application.properties (30 seconds)")
    void testLockTtlConfiguration() {
        Duration configuredTtl = lockService.getConfiguredTtl();
        assertEquals(30, configuredTtl.getSeconds(),
                "Lock TTL should be 30 seconds (from payment.lock.ttl-seconds)");
    }

    // ═══════════════════════════════════════════════
    // TEST 2: Acquire a lock successfully
    // ═══════════════════════════════════════════════

    @Test
    @Order(2)
    @DisplayName("2. Successfully acquire a lock on a wallet")
    void testAcquireLock() {
        LockHandle handle = lockService.tryLock(TEST_WALLET_ID);

        assertNotNull(handle, "Should successfully acquire lock");
        assertEquals(TEST_WALLET_ID, handle.getWalletId());
        assertNotNull(handle.getLockValue(), "Lock value (UUID) should be set");
        assertTrue(lockService.isLocked(TEST_WALLET_ID), "Wallet should be locked");

        // Clean up
        lockService.releaseLock(handle);
    }

    // ═══════════════════════════════════════════════
    // TEST 3: Double-lock is rejected (mutual exclusion)
    // ═══════════════════════════════════════════════

    @Test
    @Order(3)
    @DisplayName("3. Second lock attempt on same wallet is rejected")
    void testDoubleLockRejected() {
        // Server A acquires lock
        LockHandle serverA = lockService.tryLock(TEST_WALLET_ID);
        assertNotNull(serverA, "Server A should acquire lock");

        // Server B tries to lock the same wallet
        LockHandle serverB = lockService.tryLock(TEST_WALLET_ID);
        assertNull(serverB, "Server B should be REJECTED — wallet is already locked");

        // Wallet is still locked by Server A
        assertTrue(lockService.isLocked(TEST_WALLET_ID));

        // Clean up
        lockService.releaseLock(serverA);
    }

    // ═══════════════════════════════════════════════
    // TEST 4: Different wallets can be locked independently
    // ═══════════════════════════════════════════════

    @Test
    @Order(4)
    @DisplayName("4. Different wallets can be locked simultaneously")
    void testIndependentWalletLocks() {
        LockHandle lock1 = lockService.tryLock(TEST_WALLET_ID);
        LockHandle lock2 = lockService.tryLock(TEST_WALLET_ID_2);

        assertNotNull(lock1, "Wallet 1 lock should succeed");
        assertNotNull(lock2, "Wallet 2 lock should succeed — independent wallet");

        assertTrue(lockService.isLocked(TEST_WALLET_ID));
        assertTrue(lockService.isLocked(TEST_WALLET_ID_2));

        lockService.releaseLock(lock1);
        lockService.releaseLock(lock2);
    }

    // ═══════════════════════════════════════════════
    // TEST 5: Release unlocks the wallet
    // ═══════════════════════════════════════════════

    @Test
    @Order(5)
    @DisplayName("5. Releasing a lock makes the wallet available again")
    void testReleaseAndReacquire() {
        // Acquire
        LockHandle handle = lockService.tryLock(TEST_WALLET_ID);
        assertNotNull(handle);
        assertTrue(lockService.isLocked(TEST_WALLET_ID));

        // Release
        boolean released = lockService.releaseLock(handle);
        assertTrue(released, "Lock should be released successfully");
        assertFalse(lockService.isLocked(TEST_WALLET_ID), "Wallet should be unlocked");

        // Re-acquire (should succeed now)
        LockHandle reacquired = lockService.tryLock(TEST_WALLET_ID);
        assertNotNull(reacquired, "Should re-acquire lock after release");

        lockService.releaseLock(reacquired);
    }

    // ═══════════════════════════════════════════════
    // TEST 6: Only the owner can release the lock (Lua script safety)
    // ═══════════════════════════════════════════════

    @Test
    @Order(6)
    @DisplayName("6. Non-owner cannot release someone else's lock (Lua script protection)")
    void testOwnerOnlyRelease() {
        // Server A acquires the lock
        LockHandle realOwner = lockService.tryLock(TEST_WALLET_ID);
        assertNotNull(realOwner);

        // Create a fake handle with a different UUID (simulating Server B trying to release)
        LockHandle fakeOwner = new LockHandle(
                TEST_WALLET_ID,
                "lock:wallet:" + TEST_WALLET_ID,
                "fake-uuid-not-the-real-owner"
        );

        // Fake owner tries to release → should FAIL
        boolean fakeRelease = lockService.releaseLock(fakeOwner);
        assertFalse(fakeRelease, "Non-owner should NOT be able to release the lock");

        // Lock should still be held by real owner
        assertTrue(lockService.isLocked(TEST_WALLET_ID), "Lock should still be held");

        // Real owner releases
        boolean realRelease = lockService.releaseLock(realOwner);
        assertTrue(realRelease, "Real owner should release successfully");
    }

    // ═══════════════════════════════════════════════
    // TEST 7: Lock auto-expires after TTL (deadlock prevention)
    // ═══════════════════════════════════════════════

    @Test
    @Order(7)
    @DisplayName("7. Lock auto-expires after TTL (prevents deadlocks)")
    void testLockAutoExpiry() throws InterruptedException {
        // Set a lock with a very short TTL directly in Redis (2 seconds)
        String lockKey = "lock:wallet:" + TEST_WALLET_ID;
        stringRedisTemplate.opsForValue()
                .set(lockKey, "crashed-server-uuid", Duration.ofSeconds(2));

        // Wallet should be locked
        assertTrue(lockService.isLocked(TEST_WALLET_ID));

        // Wait for auto-expiry
        Thread.sleep(2500);

        // Lock should be gone — another server can now acquire it
        assertFalse(lockService.isLocked(TEST_WALLET_ID),
                "Lock should have auto-expired — deadlock prevention");

        // New server can acquire the lock
        LockHandle newLock = lockService.tryLock(TEST_WALLET_ID);
        assertNotNull(newLock, "Should acquire lock after auto-expiry");

        lockService.releaseLock(newLock);
    }

    // ═══════════════════════════════════════════════
    // TEST 8: Release null handle is safe (no NPE)
    // ═══════════════════════════════════════════════

    @Test
    @Order(8)
    @DisplayName("8. Releasing a null handle is safe (returns false)")
    void testReleaseNullHandle() {
        boolean result = lockService.releaseLock(null);
        assertFalse(result, "Releasing null handle should return false, not throw");
    }
}
