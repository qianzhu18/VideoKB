package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("knowledge_placements")
public class KnowledgePlacement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private Long spaceId;
    private Long collectionId;
}
