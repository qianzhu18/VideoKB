package com.example.server.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness plus real dependency probes (MySQL, Redis). Each probe is isolated:
 * one degraded component must never take the endpoint down with it —
 * orchestration healthchecks need an honest DEGRADED, not a hanging request.
 */
@Service
public class HealthService {

    private final JdbcTemplate jdbc;
    private final RedisTemplate<String, String> redis;

    public HealthService(JdbcTemplate jdbc, RedisTemplate<String, String> redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public Map<String, Object> check() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("mysql", probe(() -> jdbc.queryForObject("SELECT 1", Integer.class)));
        components.put("redis", probe(() -> {
            Boolean pong = redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>)
                    connection -> connection.ping() != null);
            if (!Boolean.TRUE.equals(pong)) throw new IllegalStateException("no pong");
        }));
        boolean allUp = components.values().stream().map(String::valueOf).noneMatch("DOWN"::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allUp ? "UP" : "DEGRADED");
        body.put("components", components);
        return body;
    }

    private static String probe(Probe probe) {
        try {
            probe.run();
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }

    @FunctionalInterface
    private interface Probe {
        void run();
    }
}
