package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_tasks")
public class AnalysisTask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    private Long mediaId;
    /** Normalized content hash shared with the Redis idempotency keys (lowercase MD5 or the media-{id} fallback). */
    private String contentHash;
    private String goalDigest;
    private String mode;
    /** One of {@link com.example.server.dto.TaskStatus.State}; written only after the state machine validates the transition. */
    private String state;
    private Integer attemptCount;
    private String lastStage;
    private String errorType;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
