package com.example.server.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

/** Stage names are bounded; never tag metrics with queries, user IDs or document IDs. */
@Service
public class KnowledgeMetrics {
    private final MeterRegistry registry;
    public KnowledgeMetrics(MeterRegistry registry) { this.registry = registry; }

    public <T> T measure(String stage, Supplier<T> action) {
        long started = System.nanoTime();
        boolean success = false;
        try { T result = action.get(); success = true; return result; }
        finally {
            Timer.builder("knowledge.stage").tag("stage", stage).tag("success", String.valueOf(success))
                    .publishPercentileHistogram().register(registry)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
    public void run(String stage, Runnable action) { measure(stage, () -> { action.run(); return null; }); }
    public void count(String event) { registry.counter("knowledge.events", "event", event).increment(); }
    public void count(String event, long amount) {
        if (amount > 0) registry.counter("knowledge.events", "event", event).increment(amount);
    }
}
