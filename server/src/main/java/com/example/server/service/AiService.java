package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.mode.ModeRegistry;
import com.example.server.utils.AnalysisTaskKeys;
import com.example.server.utils.DeepSeekUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 视频分析的应用层入口，负责串起上下文构建、AgentLoop 和结果落库。 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    /** 追问历史在 Redis 中的前缀。 */
    private static final String FOLLOW_UP_HISTORY_KEY_PREFIX = "followup:history:";
    /** 每个视频最多保留的追问轮数。 */
    private static final int FOLLOW_UP_HISTORY_MAX = 10;
    /** 追问历史的保留时间。 */
    private static final Duration FOLLOW_UP_HISTORY_TTL = Duration.ofDays(7);

    private final MediaFileMapper mediaFileMapper;
    private final VideoPreparationService preparation;
    private final LongVideoContextService longVideoContextService;
    private final AgentLoopService agentLoopService;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final MediaService mediaService;
    private final TaskEventService taskEventService;
    private final ModeRegistry modeRegistry;
    private final KnowledgeIngestJobService ingestJobs;
    private final KnowledgeSourceService knowledgeSources;
    private final StringRedisTemplate redisTemplate;
    private final DeepSeekUtils deepSeekUtils;
    private final ObjectMapper objectMapper;

    public AiService(MediaFileMapper mediaFileMapper,
                     VideoPreparationService preparation,
                     LongVideoContextService longVideoContextService,
                     AgentLoopService agentLoopService,
                     AgentCheckpointService checkpointService,
                     AgentTelemetry telemetry,
                     MediaService mediaService,
                     TaskEventService taskEventService,
                     ModeRegistry modeRegistry,
                     KnowledgeIngestJobService ingestJobs, KnowledgeSourceService knowledgeSources,
                     StringRedisTemplate redisTemplate, DeepSeekUtils deepSeekUtils, ObjectMapper objectMapper) {
        this.mediaFileMapper = mediaFileMapper;
        this.preparation = preparation;
        this.longVideoContextService = longVideoContextService;
        this.agentLoopService = agentLoopService;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.mediaService = mediaService;
        this.taskEventService = taskEventService;
        this.modeRegistry = modeRegistry;
        this.ingestJobs = ingestJobs;
        this.knowledgeSources = knowledgeSources;
        this.redisTemplate = redisTemplate;
        this.deepSeekUtils = deepSeekUtils;
        this.objectMapper = objectMapper;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 分析。 */
    public void asyncAnalyze(Long mediaId, String userGoal) {
        asyncAnalyze(mediaId, userGoal, AnalysisMode.GENERAL);
    }

    public void asyncAnalyze(Long mediaId, String userGoal, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        String traceId = telemetry.start(mediaId, userGoal, resolvedMode);
        telemetry.bind(traceId);
        TaskStage currentStage = TaskStage.VIDEO_CONTEXT;
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            telemetry.flush(traceId);
            telemetry.clear();
            throw new IllegalArgumentException("media does not exist: " + mediaId);
        }

        try {
            // Checkpoint 键 = (mediaId, goalDigest(goal, mode)):不同模式的同一目标互不串键,
            // 因此这里带 mode 读取,不会误取别的模式已完成的结果。
            AgentState agentState = checkpointService.loadResult(mediaId, userGoal, resolvedMode);
            if (agentState != null && agentState.result() != null) {
                persistResult(mediaFile, agentState, null);
                telemetry.increment(traceId, "checkpointHits", 1);
                indexKnowledge(mediaId);
                return;
            }

            VideoContext videoContext = preparation.prepare(mediaFile, userGoal, traceId, resolvedMode);
            mediaFile.setTranscriptText(videoContext.transcriptText());
            // Knowledge runs on its own durable job; the report only shares the ASR/OCR checkpoint.
            indexKnowledge(mediaId);
            currentStage = TaskStage.AGENT_LOOP;
            taskEventService.publishAnalysis(mediaId, userGoal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.PROCESSING, "多模态上下文已就绪，Agent 开始分析"),
                    TaskStage.AGENT_LOOP);
            long agentStarted = System.nanoTime();
            try {
                agentState = agentLoopService.run(mediaId, videoContext, modeRegistry.of(resolvedMode));
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, true);
            } catch (RuntimeException e) {
                telemetry.stage(traceId, TaskStage.AGENT_LOOP.name(), agentStarted, false);
                throw e;
            }
            persistResult(mediaFile, agentState, videoContext.transcriptText());
            log.info("agent_analysis_completed traceId={} mediaId={} rounds={}",
                    traceId, mediaId, agentState.round());
        } catch (Exception e) {
            try {
                checkpointService.saveFailure(mediaId, userGoal, resolvedMode, currentStage, e);
            } catch (RuntimeException checkpointError) {
                e.addSuppressed(checkpointError);
                log.error("agent_failure_checkpoint_write_failed traceId={} mediaId={}",
                        traceId, mediaId, checkpointError);
            }
            log.error("agent_analysis_failed traceId={} mediaId={}", traceId, mediaId, e);
            if (e instanceof AgentLoopService.BudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            }
            throw new IllegalStateException("AI analysis failed", e);
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    /** Compatibility entry point for legacy report consumers: enqueue knowledge work only. */
    public void indexKnowledge(Long mediaId) {
        ingestJobs.enqueue(knowledgeSources.requireSourceByMediaId(mediaId));
    }

    public String followUp(Long mediaId, String originalGoal, String question) {
        return followUp(mediaId, originalGoal, question, AnalysisMode.GENERAL);
    }

    public String followUp(Long mediaId,
                           String originalGoal,
                           String question,
                           AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, question, resolvedMode);
        telemetry.bind(traceId);
        try {
            return agentLoopService.executeWithinBudget(() -> {
                AgentState previous = originalGoal == null
                        ? null : checkpointService.loadResult(mediaId, originalGoal, resolvedMode);
                String historyKey = followUpHistoryKey(mediaId, originalGoal, resolvedMode);
                List<String> history = loadFollowUpHistory(historyKey, mediaId);
                String evidenceSummary = retrieveEvidenceWithFunctionCalling(mediaId, context, question);
                String followUpGoal = contextualQuestion(originalGoal, previous, question, history, evidenceSummary);
                VideoContext followUpContext = new VideoContext(
                        context.source(), followUpGoal, context.segments());
                String answer = agentLoopService.run(
                        mediaId, followUpContext, modeRegistry.of(resolvedMode)).result().toMarkdown();
                saveFollowUpHistory(historyKey, mediaId, question, answer);
                return answer;
            });
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    public List<VideoEvidenceHit> searchEvidence(Long mediaId, String query) {
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) throw new VideoContextNotReadyException();

        String traceId = telemetry.start(mediaId, query);
        telemetry.bind(traceId);
        long started = System.nanoTime();
        try {
            VideoContext searchContext = new VideoContext(
                    context.source(), query, context.segments());
            List<VideoEvidenceHit> hits =
                    longVideoContextService.searchEvidence(mediaId, searchContext);
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, true);
            return hits;
        } catch (RuntimeException e) {
            telemetry.stage(traceId, TaskStage.RETRIEVAL.name(), started, false);
            throw e;
        } finally {
            telemetry.flush(traceId);
            telemetry.clear();
        }
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 暂存修正。 */
    public void stageRevision(AgentFeedback feedback) {
        stageRevision(feedback, AnalysisMode.GENERAL);
    }

    public void stageRevision(AgentFeedback feedback, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        AgentFeedback normalized = feedback.normalized(resolvedMode);
        checkpointService.saveFeedback(normalized);

        String goal = normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal().trim();
        AgentState.AgentPlan correctedPlan = normalized.correctedTasks().isEmpty()
                ? null
                : new AgentState.AgentPlan(goal, normalized.correctedTasks());
        checkpointService.stageRevision(normalized.mediaId(), goal, resolvedMode, correctedPlan);
    }

    public String revisionGoal(AgentFeedback feedback) {
        AgentFeedback normalized = feedback.normalized();
        return normalized.correctedGoal() == null || normalized.correctedGoal().isBlank()
                ? normalized.goal()
                : normalized.correctedGoal();
    }

    public void cancelStagedRevision(Long mediaId, String goal) {
        cancelStagedRevision(mediaId, goal, AnalysisMode.GENERAL);
    }

    public void cancelStagedRevision(Long mediaId, String goal, AnalysisMode mode) {
        checkpointService.cancelStagedRevision(mediaId, goal, mode);
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 复用。 */
    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state) {
        return reuseResult(mediaId, sourceMediaId, state, AnalysisMode.GENERAL);
    }

    public boolean reuseResult(Long mediaId, Long sourceMediaId, AgentState state, AnalysisMode mode) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new IllegalArgumentException("media does not exist: " + mediaId);

        VideoContext sourceContext = checkpointService.loadContext(sourceMediaId);
        if (sourceContext == null) return false;
        if (!mediaId.equals(sourceMediaId)) {
            checkpointService.saveContext(mediaId, reusableContext(mediaFile.getFilePath(), sourceContext));
        }
        checkpointService.saveResult(mediaId, new AgentState(
                state.goal(), state.plan(), state.result(), state.critique(), state.round()), mode);
        persistResult(mediaFile, state, null);
        return true;
    }

    private VideoContext reusableContext(String targetSource, VideoContext sourceContext) {
        return new VideoContext(targetSource, "", sourceContext.segments().stream()
                .map(segment -> new VideoContext.VideoSegment(
                        segment.startMs(),
                        segment.endMs(),
                        segment.transcript(),
                        segment.ocrTexts(),
                        segment.evidenceFrames().isEmpty()
                                ? java.util.List.of()
                                : java.util.List.of(targetSource + "#timestampMs=" + segment.startMs())))
                .toList());
    }

    private String contextualQuestion(String originalGoal, AgentState previous, String question, List<String> history, String evidenceSummary) {
        StringBuilder sb = new StringBuilder("这是对同一视频的继续追问。请结合原始视频证据和已有分析回答当前问题。\n");
        if (originalGoal != null && !originalGoal.isBlank()) {
            sb.append("原始目标：").append(originalGoal).append("\n");
        }
        if (previous != null && previous.result() != null) {
            String previousResult = previous.result().toMarkdown();
            if (previousResult.length() > 4_000) previousResult = previousResult.substring(0, 4_000);
            sb.append("已有分析：").append(previousResult).append("\n");
        }
        if (history != null && !history.isEmpty()) {
            sb.append("历史对话：\n");
            for (String item : history) {
                sb.append(item).append("\n");
            }
        }
        if (evidenceSummary != null && !evidenceSummary.isBlank()) {
            sb.append("Function Calling 检索到的证据：\n").append(evidenceSummary).append("\n");
        }
        sb.append("当前追问：").append(question);
        return sb.toString();
    }

    private List<String> loadFollowUpHistory(String key, Long mediaId) {
        try {
            List<String> history = redisTemplate.opsForList().range(key, 0, FOLLOW_UP_HISTORY_MAX - 1);
            return history == null ? List.of() : history;
        } catch (Exception e) {
            log.warn("读取追问历史失败 mediaId={}", mediaId, e);
            return List.of();
        }
    }

    private void saveFollowUpHistory(String key, Long mediaId, String question, String answer) {
        try {
            String entry = "问：" + question + "\n答：" + answer;
            redisTemplate.opsForList().rightPush(key, entry);
            redisTemplate.opsForList().trim(key, -FOLLOW_UP_HISTORY_MAX, -1);
            redisTemplate.expire(key, FOLLOW_UP_HISTORY_TTL);
        } catch (Exception e) {
            log.warn("保存追问历史失败 mediaId={}", mediaId, e);
        }
    }

    private String followUpHistoryKey(Long mediaId, String originalGoal, AnalysisMode mode) {
        String scopedGoal = originalGoal == null || originalGoal.isBlank()
                ? "__FOLLOW_UP_WITHOUT_ORIGINAL_GOAL__"
                : originalGoal;
        return FOLLOW_UP_HISTORY_KEY_PREFIX + mediaId + ":"
                + AnalysisTaskKeys.goalDigest(scopedGoal, mode);
    }

    /**
     * 轻量 Function Calling：让模型通过 searchEvidence 工具检索视频证据，
     * 再把检索结果摘要返回给上层。任何异常都不会阻断追问主流程。
     */
    private String retrieveEvidenceWithFunctionCalling(Long mediaId, VideoContext context, String question) {
        try {
            ToolSpecification searchTool = ToolSpecification.builder()
                    .name("searchEvidence")
                    .description("在视频的时间轴证据中检索与问题相关的片段，返回时间戳、语音文字和 OCR 文本。")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("query", "检索关键词或问题描述")
                            .required("query")
                            .build())
                    .build();

            String systemPrompt = """
                    你是视频证据检索助手。根据用户的追问，调用 searchEvidence 工具检索相关视频证据。
                    如果一次检索不够，可以多次调用。最后用简短中文总结检索到的证据要点。
                    """;
            String userPrompt = "当前追问：" + question;

            return deepSeekUtils.chatWithTools(
                    "FOLLOW_UP_RETRIEVAL",
                    systemPrompt,
                    userPrompt,
                    List.of(searchTool),
                    arguments -> {
                        String query = extractQuery(arguments);
                        VideoContext searchContext = new VideoContext(
                                context.source(), query, context.segments());
                        List<VideoEvidenceHit> hits = longVideoContextService.searchEvidence(mediaId, searchContext);
                        return formatHits(hits);
                    },
                    3
            );
        } catch (AgentExecutionBudget.DeadlineExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Function Calling 检索证据失败，跳过增强检索 mediaId={}", mediaId, e);
            return "";
        }
    }

    private String extractQuery(String arguments) {
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode query = node.get("query");
            return query == null ? "" : query.asText();
        } catch (Exception e) {
            log.warn("解析 searchEvidence 参数失败: {}", arguments, e);
            return "";
        }
    }

    private String formatHits(List<VideoEvidenceHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "未检索到相关证据";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(hits.size(), 5);
        for (int i = 0; i < limit; i++) {
            VideoEvidenceHit hit = hits.get(i);
            sb.append(i + 1).append(". [")
                    .append(hit.startMs()).append("ms-").append(hit.endMs()).append("ms] ")
                    .append(hit.snippet() == null ? "" : hit.snippet()).append("\n");
            if (hit.transcript() != null && !hit.transcript().isBlank()) {
                sb.append("语音：").append(hit.transcript()).append("\n");
            }
            if (hit.ocrTexts() != null && !hit.ocrTexts().isEmpty()) {
                sb.append("OCR：").append(String.join(" ", hit.ocrTexts())).append("\n");
            }
        }
        return sb.toString();
    }

    private void persistResult(MediaFile mediaFile, AgentState agentState, String transcript) {
        if (agentState.result() == null) throw new IllegalStateException("Agent 未生成分析结果");
        MediaFile update = new MediaFile();
        update.setId(mediaFile.getId());
        update.setAiSummary(agentState.result().toMarkdown());
        // Do not write back the stale transcript loaded before the long-running analysis.
        mediaFileMapper.updateById(update);
        // Preserve the existing analysis-to-transcript behavior without overwriting
        // a separate transcription that completed while the Agent was running.
        if (transcript != null && !transcript.isBlank()) {
            MediaFile transcriptUpdate = new MediaFile();
            transcriptUpdate.setTranscriptText(transcript);
            mediaFileMapper.update(transcriptUpdate, new UpdateWrapper<MediaFile>()
                    .eq("id", mediaFile.getId())
                    .and(query -> query.isNull("transcript_text").or().eq("transcript_text", "")));
        }
        mediaService.invalidateUserList(mediaFile.getUserId());
    }
}
