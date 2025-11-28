package com.tus.upload.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Global async executor using Java 21 virtual threads.
     * All @Async methods will automatically run on virtual threads.
     */
    @Bean
    public Executor taskExecutor() {
        return new VirtualThreadTaskExecutor("asset-service-task-");
    }
}
