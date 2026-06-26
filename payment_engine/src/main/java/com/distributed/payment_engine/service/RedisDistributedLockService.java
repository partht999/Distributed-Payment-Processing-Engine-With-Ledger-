package com.distributed.payment_engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/** Redis Distributed Lock Service.
 *
 * THE PROBLEM:
 * In a distributed system with multiple server instances, two servers could
 * pick up the same payment and process it simultaneously. Both would debit
 * the sender's wallet, causing a DOUBLE CHARGE.
 *
 * THE SOLUTION:
 * Before processing a payment, a server must acquire a lock on the wallet(s)
 * involved. The lock is stored in Redis (shared across all servers).
 * If another server already holds the lock, the second server must wait or fail.
 *
 * THE PATTERN (Redis "SET NX EX" lock):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ ACQUIRE: SET lock:wallet:{id} {uuid} NX EX 30              │
 * │   - NX ensures only one server can hold the lock            │
 * │   - EX 30 auto-releases after 30s (prevents deadlock)       │
 * │   - UUID identifies the lock owner (prevents accidental     │
 * │     release by another server)                              │
 * │                                                             │
 * │ RELEASE: (Lua script — atomic check-and-delete)             │
 * │   if redis.call("get", key) == uuid then                    │
 * │       return redis.call("del", key)                         │
 * │   end                                                       │
 * │   return 0                                                  │
 * └─────────────────────────────────────────────────────────────┘
 *
 * WHY A LUA SCRIPT FOR RELEASE?
 * Without it, a race condition exists:
 *   1. Server A checks: "is this my lock?" → YES
 *   2. Server A's lock expires (TTL hit) between check and delete
 *   3. Server B acquires the lock
 *   4. Server A deletes the key → Server B's lock is destroyed!
 *
 * The Lua script executes GET + DEL as a single atomic operation in Redis,
 * making this race condition impossible.
 *
 * KEY FORMAT: "lock:wallet:{walletId}"
 * VALUE: UUID (unique per lock acquisition)
 */
@Service
public class RedisDistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLockService.class);

    private static final String LOCK_PREFIX = "lock:wallet:";

    /**
     * Lua script for safe lock release.
     * Only deletes the key if the value matches the owner's UUID.
     * This prevents Server A from accidentally releasing Server B's lock.
     */
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "   return redis.call('del', KEYS[1]) " +
            "else " +
            "   return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final Duration lockTtl;

    /**
     * Lock TTL is configurable via application.properties.
     * Default: 30 seconds — long enough for a payment to process,
     * short enough to auto-release if a server crashes.
     */
    public RedisDistributedLockService(
            StringRedisTemplate redisTemplate,
            @Value("${payment.lock.ttl-seconds:30}") long lockTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
        log.info("[DistributedLock] Initialized with TTL = {} seconds", lockTtlSeconds);
    }

    /**
     * Attempt to acquire a distributed lock on a wallet.
     *
     * @param walletId The wallet to lock
     * @return A LockHandle if acquired (contains the UUID needed for release),
     *         or null if the lock is already held by another server
     */
    public LockHandle tryLock(Long walletId) {
        String lockKey = LOCK_PREFIX + walletId;
        String lockValue = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, lockTtl);

        if (Boolean.TRUE.equals(acquired)) {
            log.info("[DistributedLock] Acquired lock: {} (owner={})", lockKey, lockValue);
            return new LockHandle(walletId, lockKey, lockValue);
        } else {
            String currentOwner = redisTemplate.opsForValue().get(lockKey);
            log.warn("[DistributedLock] Failed to acquire lock: {} (held by {})", lockKey, currentOwner);
            return null;
        }
    }

    /**
     * Release a distributed lock.
     * Uses a Lua script to atomically check ownership before deleting.
     *
     * @param handle The LockHandle returned by tryLock()
     * @return true if the lock was released, false if it was already expired or held by another owner
     */
    public boolean releaseLock(LockHandle handle) {
        if (handle == null) return false;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(handle.getLockKey()),
                handle.getLockValue());

        boolean released = result != null && result == 1;
        if (released) {
            log.info("[DistributedLock] Released lock: {} (owner={})", handle.getLockKey(), handle.getLockValue());
        } else {
            log.warn("[DistributedLock] Could not release lock: {} (expired or owner mismatch)", handle.getLockKey());
        }
        return released;
    }

    /**
     * Check if a wallet is currently locked.
     *
     * @param walletId The wallet to check
     * @return true if the wallet is locked, false if it's free
     */
    public boolean isLocked(Long walletId) {
        String lockKey = LOCK_PREFIX + walletId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey));
    }

    /**
     * Get the configured lock TTL.
     * Exposed for testing and monitoring.
     */
    public Duration getConfiguredTtl() {
        return lockTtl;
    }

    /**
     * A handle representing an acquired lock.
     * Contains the key and UUID needed to release the lock safely.
     * Implements AutoCloseable for use in try-with-resources (future enhancement).
     */
    public static class LockHandle {
        private final Long walletId;
        private final String lockKey;
        private final String lockValue;

        public LockHandle(Long walletId, String lockKey, String lockValue) {
            this.walletId = walletId;
            this.lockKey = lockKey;
            this.lockValue = lockValue;
        }

        public Long getWalletId() { return walletId; }
        public String getLockKey() { return lockKey; }
        public String getLockValue() { return lockValue; }

        @Override
        public String toString() {
            return "LockHandle{wallet=" + walletId + ", key=" + lockKey + ", owner=" + lockValue + "}";
        }
    }
}
