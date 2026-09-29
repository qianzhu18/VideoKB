package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operational snapshot for admins: index coverage, stuck sources, link backlog
 * and the failed-analysis queue that decides whether a replay is needed.
 */
@RestController
@RequestMapping("/admin/stats")
public class AdminStatsController {

    private final KnowledgeStatsService statsService;
    private final AuthService authService;

    public AdminStatsController(KnowledgeStatsService statsService, AuthService authService) {
        this.statsService = statsService;
        this.authService = authService;
    }

    @GetMapping
    public Result<Map<String, Object>> stats(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        authService.requireAdmin(userId);
        return Result.ok(statsService.snapshot());
    }
}
