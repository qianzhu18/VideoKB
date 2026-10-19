package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.MukuBatchRequest;
import com.example.server.service.AuthService;
import com.example.server.service.MukuBatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/media")
public class MukuBatchController {

    private final MukuBatchService batchService;

    public MukuBatchController(MukuBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping("/muku/status")
    public Result<Map<String, Object>> status(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        return Result.ok(batchService.status());
    }

    @PostMapping("/batch-import")
    public Result<Map<String, Object>> submit(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody MukuBatchRequest request) {
        return Result.ok(batchService.submit(userId, request));
    }

    @GetMapping("/batch-import/{batchId}")
    public Result<Map<String, Object>> status(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable String batchId) {
        return Result.ok(batchService.get(userId, batchId));
    }
}
