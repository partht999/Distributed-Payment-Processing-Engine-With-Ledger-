package com.distributed.payment_engine.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final StringRedisTemplate redisTemplate;

    public HealthController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "message", "Distributed Payment Processing Engine",
                "version", "1.0.0",
                "health", "/api/v1/health"
        );
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "Payment Engine");
        response.put("timestamp", Instant.now().toString());

        // Redis health check
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection().ping();
            response.put("redis", pong != null ? "UP" : "DOWN");
        } catch (Exception e) {
            response.put("redis", "DOWN");
            response.put("redisError", e.getMessage());
        }

        return response;
    }
}


