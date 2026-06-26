package com.distributed.payment_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration for the Payment Engine.
 *
 * This class configures how Spring Data Redis connects to and communicates
 * with the Redis instance. We define two templates:
 *
 * 1. RedisTemplate<String, Object> — For storing complex objects as JSON.
 *    Uses StringRedisSerializer for keys (human-readable in redis-cli)
 *    and GenericJackson2JsonRedisSerializer for values (automatic JSON
 *    serialization/deserialization with type information).
 *
 * 2. StringRedisTemplate — For simple string key-value operations.
 *    This is what we'll use for idempotency keys (SET NX EX) and
 *    processing markers in upcoming days.
 *
 * The RedisConnectionFactory is auto-configured by Spring Boot from the
 * application.properties settings (spring.data.redis.*). It uses the
 * Lettuce client (non-blocking, Netty-based) by default.
 *
 * WHY LETTUCE OVER JEDIS?
 * - Lettuce is non-blocking (uses Netty under the hood)
 * - Thread-safe: single connection shared across threads
 * - Supports reactive and async operations
 * - Default in Spring Boot since version 2.0
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate for storing complex Java objects in Redis.
     *
     * Key serialization: StringRedisSerializer
     *   - Keys are always strings (e.g., "idempotency:abc-123")
     *   - Human-readable when inspecting via redis-cli
     *
     * Value serialization: GenericJackson2JsonRedisSerializer
     *   - Automatically serializes any Java object to JSON
     *   - Includes @class type info for deserialization
     *   - Supports nested objects, lists, maps, etc.
     *
     * Hash key/value: Same serializers for hash operations
     *
     * We'll use this template in later days for storing:
     *   - Idempotency responses *   - Processing markers *   - Distributed lock metadata */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys are always plain strings — readable in redis-cli
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Values are JSON — supports any Java object
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate for simple string-to-string operations.
     *
     * This is a convenience template where both keys and values are strings.
     * Perfect for:
     *   - SET key NX EX 300 (idempotency with TTL)
     *   - Simple flags and counters
     *   - Processing marker keys ("processing:payment:42")
     *
     * Spring Boot auto-configures this bean, but we define it explicitly
     * for clarity and to make the config self-documenting.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
