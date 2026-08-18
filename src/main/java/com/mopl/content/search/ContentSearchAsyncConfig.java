package com.mopl.content.search;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ContentSearchAsyncConfig {

    @Bean
    public Executor contentSearchSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("content-search-sync-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.initialize();
        return executor;
    }
}