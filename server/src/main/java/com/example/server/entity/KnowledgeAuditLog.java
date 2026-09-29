package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Mutation-only audit record. It stores identifiers and short operation metadata, never source content. */
@Data
@TableName("knowledge_audit_logs")
public class KnowledgeAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private Long spaceId;
    private Long collectionId;
    private String details;
    private LocalDateTime createdAt;
}
