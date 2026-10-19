package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A typed relation between two knowledge assets. Suggestions are machine opinions
 * (status=SUGGESTED) and never facts; only explicit user action confirms a link.
 */
@Data
@TableName("knowledge_links")
public class KnowledgeLink {

    /** Owning side of the relation, e.g. the SCRIPT source. */
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private Long targetSourceId;
    private String sourceSegmentId;
    private String targetSegmentId;
    private String linkType;
    private String status;
    private BigDecimal confidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
