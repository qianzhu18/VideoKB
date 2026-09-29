package com.example.server.service;

import com.example.server.entity.KnowledgeAuditLog;
import com.example.server.mapper.KnowledgeAuditLogMapper;
import org.springframework.stereotype.Service;

/**
 * The knowledge APIs are user-scoped, so write operations need a durable trail for debugging,
 * support and future MCP authorization reviews. Payloads deliberately stay small and content-free.
 */
@Service
public class KnowledgeAuditService {

    private final KnowledgeAuditLogMapper auditLogMapper;

    public KnowledgeAuditService(KnowledgeAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(Long userId, String action, String resourceType, Long resourceId,
                       Long spaceId, Long collectionId, String details) {
        KnowledgeAuditLog log = new KnowledgeAuditLog();
        log.setOwnerUserId(userId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setSpaceId(spaceId);
        log.setCollectionId(collectionId);
        log.setDetails(truncate(details));
        auditLogMapper.insert(log);
    }

    private String truncate(String details) {
        if (details == null) return "";
        return details.length() <= 1000 ? details : details.substring(0, 1000);
    }
}
