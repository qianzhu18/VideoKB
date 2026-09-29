package com.example.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Health contract: isolated probes, honest DEGRADED, never a hanging request.
 */
class HealthServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void reportsUpWhenAllProbesPong() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.execute(any(org.springframework.data.redis.core.RedisCallback.class))).thenReturn(true);

        Map<String, Object> body = new HealthService(jdbc, redis).check();

        assertEquals("UP", body.get("status"));
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertEquals("UP", components.get("mysql"));
        assertEquals("UP", components.get("redis"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradesButDoesNotThrowWhenAComponentIsDown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new RuntimeException("connection refused"));
        // execute() 默认返回 null → ping 判失败 → redis DOWN
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);

        Map<String, Object> body = new HealthService(jdbc, redis).check();

        assertEquals("DEGRADED", body.get("status"));
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertEquals("DOWN", components.get("mysql"));
        assertEquals("DOWN", components.get("redis"));
    }
}
