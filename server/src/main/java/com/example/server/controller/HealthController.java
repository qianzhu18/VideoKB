package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness with real dependency probes. HTTP 200 = all components UP,
 * 503 = at least one DOWN, so orchestrator healthchecks fail honestly.
 */
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<Result<Map<String, Object>>> health() {
        Map<String, Object> body = healthService.check();
        boolean allUp = "UP".equals(body.get("status"));
        return ResponseEntity.status(allUp ? 200 : 503).body(Result.ok(body));
    }
}
