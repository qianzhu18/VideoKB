/**
 * Analysis-task lifecycle management: the explicit state machine
 * ({@link com.example.server.service.task.AnalysisTaskStateMachine}) and the
 * durable task ledger ({@link com.example.server.service.task.AnalysisTaskService}).
 *
 * <p>The Redis idempotency keys remain the runtime authority; the ledger is the
 * durable, reconcilable record. Ledger write failures degrade to warnings and
 * never block the analysis pipeline.
 */
package com.example.server.service.task;
