package com.mopl.content.search;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

/**
 * 같은 contentId의 작업은 항상 같은 레인(단일 스레드)으로 보내 제출 순서대로 처리되게 한다.
 * 서로 다른 contentId는 다른 레인에서 병렬로 처리될 수 있다.
 *
 * <p>{@code @Component}가 아니다 — {@link ContentSearchAsyncConfig}의 {@code @Bean} 메서드에서
 * 레인 목록을 직접 구성해 넘겨준다. Spring이 {@code List<Executor>}를 자동 주입하게 두면
 * 다른 {@code Executor} 빈(watcherCount 리프레시용 {@code contentSearchSyncExecutor} 등)까지
 * 섞여 들어올 수 있어서 명시적으로 구성한다.
 *
 * <p>{@link DisposableBean}을 구현한다 — 레인 executor들은 이 클래스 안에만 감싸져 있어
 * Spring이 개별 빈으로 인식하지 못하므로, 컨텍스트 종료 시 자동으로 shutdown 되지 않는다.
 * 여기서 직접 정리하지 않으면 non-daemon 스레드가 살아남아 그레이스풀 셧다운을 지연시킨다.
 */
@Slf4j
public class ContentSearchKeyedExecutor implements DisposableBean {

    private final List<Executor> lanes;

    public ContentSearchKeyedExecutor(List<Executor> lanes) {
        this.lanes = lanes;
    }

    public void execute(UUID contentId, Runnable task) {
        int laneIndex = Math.floorMod(contentId.hashCode(), lanes.size());
        lanes.get(laneIndex).execute(task);
    }

    @Override
    public void destroy() {
        for (Executor lane : lanes) {
            if (lane instanceof DisposableBean disposableBean) {
                try {
                    disposableBean.destroy();
                } catch (Exception e) {
                    log.warn("콘텐츠 검색 동기화 레인 종료 중 오류가 발생했습니다.", e);
                }
            } else if (lane instanceof ExecutorService executorService) {
                executorService.shutdown();
            }
        }
    }
}
