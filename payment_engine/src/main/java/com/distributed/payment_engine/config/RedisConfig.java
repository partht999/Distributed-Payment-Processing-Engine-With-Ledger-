package com.distributed.payment_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis Configuration for the Payment Engine.
 *
 * Configures THREE Redis use cases:
 *
 * 1. RedisTemplate<String, Object>  — For storing complex objects as JSON.
 * 2. StringRedisTemplate            — For idempotency keys (SET NX EX) and processing markers.
 * 3. RedisCacheManager              — For Spring @Cacheable read-through caching.
 *
 * CACHING STRATEGY:
 * ┌────────────────────────────────────────────────────────────────────┐
 * │  GET /wallets/{id}                                                │
 * │  ├── Check Redis cache (key: "wallets::42")                       │
 * │  │   ├── HIT  → Return cached wallet (sub-millisecond, no DB)    │
 * │  │   └── MISS → Query PostgreSQL → Store in Redis → Return       │
 * │  │                                                                │
 * │  POST /wallets/{id}/deposit (or transfer/withdraw)                │
 * │  ├── @CacheEvict("wallets") → Invalidates cached wallet data     │
 * │  └── Next GET will re-query DB and refresh cache                  │
 * └────────────────────────────────────────────────────────────────────┘
 *
 * Cache TTL: 10 minutes (configurable via spring.cache.redis.time-to-live)
 * This ensures stale data is automatically evicted even if a write bypasses
 * the cache eviction (e.g., direct DB update via SQL migration).
 */
@Configuration
public class RedisConfig {

    /**
     * RedisCacheManager for Spring @Cacheable annotations.
     *
     * Values are serialized as JSON so they can be inspected in redis-cli.
     * Keys use the format: {cacheName}::{key} (e.g., "wallets::42")
     *
     * TTL = 10 minutes. After expiry, the next read will hit PostgreSQL
     * and repopulate the cache. This provides a safety net against
     * stale cache entries that weren't explicitly evicted.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * RedisTemplate for storing complex Java objects in Redis.
     *
     * Key serialization:   StringRedisSerializer (human-readable in redis-cli)
     * Value serialization: GenericJackson2JsonRedisSerializer (automatic JSON)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate for simple string-to-string operations.
     *
     * Used for:
     *   - SET key NX EX 300 (idempotency with TTL)
     *   - Simple flags and counters
     *   - Processing marker keys ("processing:payment:42")
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
