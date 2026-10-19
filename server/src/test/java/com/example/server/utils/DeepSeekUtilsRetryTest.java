package com.example.server.utils;

import com.example.server.service.AgentExecutionBudget;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-classification contract for model calls: only explicit protocol-level
 * non-retriable signals (4xx, NonRetriableException, exhausted time budget) may
 * dead-letter a run; unknown transport-level failures must retry.
 */
class DeepSeekUtilsRetryTest {

    @Test
    void eofMidBodyIsRetriable() {
        // Exact production shape: langchain4j wraps the raw stream EOF in a bare
        // RuntimeException with no HTTP status — the case that dead-lettered an
        // 8-minute agent run on its first attempt.
        RuntimeException eof = new RuntimeException(
                new IOException("EOF reached while reading", new EOFException("EOF reached while reading")));
        assertTrue(DeepSeekUtils.isRetriableModelFailure(eof));
    }

    @Test
    void connectionLevelIoErrorsAreRetriable() {
        assertTrue(DeepSeekUtils.isRetriableModelFailure(
                new RuntimeException(new java.net.ConnectException("connection refused"))));
        assertTrue(DeepSeekUtils.isRetriableModelFailure(
                new RuntimeException(new java.net.SocketTimeoutException("read timed out"))));
    }

    @Test
    void explicitRetriableMarkerWins() {
        assertTrue(DeepSeekUtils.isRetriableModelFailure(new RetriableException("模型调用超时")));
        // Marker found deeper in the chain still counts.
        assertTrue(DeepSeekUtils.isRetriableModelFailure(
                new RuntimeException(new RetriableException("线程池繁忙"))));
    }

    @Test
    void explicitNonRetriableMarkerIsPermanent() {
        assertFalse(DeepSeekUtils.isRetriableModelFailure(new NonRetriableException("模型拒绝请求")));
        assertFalse(DeepSeekUtils.isRetriableModelFailure(
                new RuntimeException(new NonRetriableException("内容策略拦截"))));
    }

    @Test
    void httpStatusClassifiesTransience() {
        assertTrue(DeepSeekUtils.isRetriableModelFailure(new HttpException(429, "rate limited")));
        assertTrue(DeepSeekUtils.isRetriableModelFailure(new HttpException(500, "server error")));
        assertTrue(DeepSeekUtils.isRetriableModelFailure(new HttpException(408, "request timeout")));
        assertFalse(DeepSeekUtils.isRetriableModelFailure(new HttpException(401, "bad key")));
        assertFalse(DeepSeekUtils.isRetriableModelFailure(new HttpException(400, "bad request")));
    }

    @Test
    void exhaustedTimeBudgetIsNotRetriable() {
        assertFalse(DeepSeekUtils.isRetriableModelFailure(
                new AgentExecutionBudget.DeadlineExceededException("Agent 已耗尽执行时长预算")));
    }

    @Test
    void deepChainsAreStillWalked() {
        RuntimeException deep = new RuntimeException("wrap1",
                new RuntimeException("wrap2", new RetriableException("deep marker")));
        assertTrue(DeepSeekUtils.isRetriableModelFailure(deep));
    }
}
