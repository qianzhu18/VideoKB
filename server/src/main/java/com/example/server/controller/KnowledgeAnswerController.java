package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.KnowledgeAnswer;
import com.example.server.dto.KnowledgeAskRequest;
import com.example.server.service.AuthService;
import com.example.server.service.KnowledgeAnswerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/** Cross-video RAG generation endpoint; all returned claims must carry verified evidence. */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeAnswerController {

    private final KnowledgeAnswerService answerService;
    private final ThreadPoolTaskExecutor knowledgeAnswerExecutor;

    public KnowledgeAnswerController(KnowledgeAnswerService answerService,
                                     @Qualifier("knowledgeAnswerExecutor") ThreadPoolTaskExecutor knowledgeAnswerExecutor) {
        this.answerService = answerService;
        this.knowledgeAnswerExecutor = knowledgeAnswerExecutor;
    }

    @PostMapping("/ask")
    public Result<KnowledgeAnswer> ask(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeAskRequest request) {
        return Result.ok(answerService.ask(userId, request));
    }

    /**
     * Streaming browser endpoint. The final event is emitted only after the same citation
     * verification used by /ask; token events are explicitly provisional draft text.
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId,
            @Valid @RequestBody KnowledgeAskRequest request) {
        SseEmitter emitter = new SseEmitter(330_000L);
        knowledgeAnswerExecutor.execute(() -> {
            try {
                KnowledgeAnswer answer = answerService.askStreaming(userId, request,
                        phase -> send(emitter, "phase", Map.of("phase", phase)),
                        text -> send(emitter, "token", Map.of("text", text)));
                send(emitter, "complete", answer);
                emitter.complete();
            } catch (Exception exception) {
                String message = exception.getMessage() == null ? "知识库回答失败，请稍后重试" : exception.getMessage();
                try {
                    send(emitter, "error", Map.of("message", message));
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    emitter.completeWithError(exception);
                }
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException exception) {
            throw new IllegalStateException("客户端已断开流式回答", exception);
        }
    }
}
