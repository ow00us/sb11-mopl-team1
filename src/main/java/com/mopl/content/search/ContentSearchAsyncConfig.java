package com.mopl.content.search;

import java.util.ArrayList;
import java.util.List;
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

    private static final int SYNC_LANE_COUNT = 4;

    // watcherCount 리프레시 배치 전용. 콘텐츠 단위 순서 보장이 필요 없어 기존 방식 그대로 둔다.
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

    // 콘텐츠 생성·수정·삭제 동기화 전용. 같은 contentId는 항상 같은 레인(단일 스레드)에서
    // 제출 순서대로 처리돼, sync/delete 작업이 뒤섞여 삭제된 문서가 되살아나는 걸 막는다.
    @Bean
    public ContentSearchKeyedExecutor contentSearchKeyedExecutor() {
        List<Executor> lanes = new ArrayList<>();
        for (int i = 0; i < SYNC_LANE_COUNT; i++) {
            ThreadPoolTaskExecutor lane = new ThreadPoolTaskExecutor();
            lane.setThreadNamePrefix("content-search-lane-" + i + "-");
            lane.setCorePoolSize(1);
            lane.setMaxPoolSize(1);
            lane.setQueueCapacity(500);
            lane.setRejectedExecutionHandler((task, exec) ->
                    log.warn("콘텐츠 검색 동기화 레인 큐가 가득 차 이번 작업을 버립니다. queueSize={}, activeCount={}",
                            exec.getQueue().size(), exec.getActiveCount()));
            lane.initialize();
            lanes.add(lane);
        }
        return new ContentSearchKeyedExecutor(lanes);
    }
}
