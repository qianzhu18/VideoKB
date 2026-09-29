package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Free-form label on a knowledge source; uniqueness is enforced per source by the schema. */
@Data
@TableName("knowledge_source_tags")
public class KnowledgeSourceTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceId;
    private String tag;
    private LocalDateTime createdAt;
}
