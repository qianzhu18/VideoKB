package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class KnowledgeRetrievalConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService knowledgeRetrievalExecutor() {
        // Bound concurrent recall work; saturation applies backpressure to the caller.
        return new ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                Thread.ofPlatform().name("knowledge-recall-", 0).factory(), new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
