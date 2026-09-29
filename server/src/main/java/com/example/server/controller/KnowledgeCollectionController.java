package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeCollectionCreateRequest;
import com.example.server.dto.KnowledgeCollectionUpdateRequest;
import com.example.server.dto.KnowledgeCollectionView;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeCollectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/knowledge")
public class KnowledgeCollectionController {

    private final KnowledgeCollectionService collectionService;

    public KnowledgeCollectionController(KnowledgeCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @GetMapping("/spaces/{spaceId}/collections")
    public Result<List<KnowledgeCollectionView>> list(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long spaceId) {
        return Result.ok(collectionService.list(userId, spaceId));
    }

    @PostMapping("/spaces/{spaceId}/collections")
    public Result<KnowledgeCollectionView> create(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long spaceId,
            @Valid @RequestBody KnowledgeCollectionCreateRequest request) {
        return Result.ok(collectionService.create(userId, spaceId, request));
    }

    @PatchMapping("/collections/{collectionId}")
    public Result<KnowledgeCollectionView> update(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long collectionId,
            @Valid @RequestBody KnowledgeCollectionUpdateRequest request) {
        return Result.ok(collectionService.update(userId, collectionId, request));
    }

    @DeleteMapping("/collections/{collectionId}")
    public Result<Void> delete(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @PathVariable Long collectionId) {
        collectionService.delete(userId, collectionId);
        return Result.ok();
    }
}
