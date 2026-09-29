package com.example.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record MukuBatchRequest(
        @NotEmpty(message = "请至少填写一个视频链接")
        @Size(max = 100, message = "单批最多导入 100 条链接")
        List<@NotBlank @Size(max = 4096) String> links,
        Long spaceId,
        Long collectionId,
        @Size(max = 500, message = "自定义解析目标不能超过 500 个字符") String analysisGoal,
        @Min(value = 1, message = "并发数至少为 1")
        @Max(value = 8, message = "并发数不能超过 8") Integer jobs,
        @Size(max = 64) String batchId
) {
}
