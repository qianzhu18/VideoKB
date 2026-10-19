package com.example.server.dto;

/** Readiness describes the authorized range, independently of relevance or answer quality. */
public record KnowledgeQueryState(Long spaceId, Long collectionId, String status,
                                 int readySources, int pendingSources, int failedSources) {
    public String warning() {
        return switch (status) {
            case "NOT_READY" -> "当前范围的资料尚未就绪，请查看入库任务后重试。";
            case "FAILED" -> "当前范围的资料入库失败，请查看任务并重试。";
            case "PARTIAL" -> "当前范围仍有未就绪或失败资料，本次仅查询已发布的内容。";
            case "EMPTY" -> "当前范围没有资料，请导入资料或切换知识范围。";
            default -> "";
        };
    }
}
