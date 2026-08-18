package com.mopl.content.search;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Slf4j
@Configuration
@EnableAsync
public class ContentSearchAsyncConfig {

    @Bean
    public Executor contentSearchSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("content-search-sync-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        // 예외를 던지는 기본 정책(AbortPolicy) 대신 조용히 버리고 로그만 남긴다.
        // 이 executor는 AFTER_COMMIT 콜백에서 호출되는데, 여기서 예외가 던져지면
        // 이미 커밋된 콘텐츠 CUD 요청 자체가 실패로 보일 수 있어서 반드시 피해야 한다.
        executor.setRejectedExecutionHandler((task, exec) ->
                log.warn("검색 동기화 작업 큐가 가득 차 이번 작업을 버립니다. queueSize={}, activeCount={}",
                        exec.getQueue().size(), exec.getActiveCount()));
        executor.initialize();
        return executor;
    }
}
