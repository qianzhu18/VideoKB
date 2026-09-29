package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A user-owned boundary for sources, retrieval and future MCP grants. */
@Data
@TableName("knowledge_spaces")
public class KnowledgeSpace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private String name;
    private String description;
    @TableField("is_system_default")
    private Boolean systemDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
