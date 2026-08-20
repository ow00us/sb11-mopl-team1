package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ContentSearchKeyedExecutorTest {

    private final List<ThreadPoolTaskExecutor> realLanes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        realLanes.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    @Test
    @DisplayName("같은 contentId는 항상 같은 레인으로 라우팅된다")
    void execute_sameContentId_alwaysRoutesToSameLane() {
        List<Integer> executedLaneIndexes = new ArrayList<>();
        List<Executor> lanes = List.of(
                task -> { executedLaneIndexes.add(0); task.run(); },
                task -> { executedLaneIndexes.add(1); task.run(); },
                task -> { executedLaneIndexes.add(2); task.run(); },
                task -> { executedLaneIndexes.add(3); task.run(); });
        ContentSearchKeyedExecutor executor = new ContentSearchKeyedExecutor(lanes);
        UUID contentId = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            executor.execute(contentId, () -> {});
        }

        assertThat(new HashSet<>(executedLaneIndexes)).hasSize(1);
    }

    @Test
    @DisplayName("같은 contentId로 제출한 작업은 제출한 순서대로 실행된다")
    void execute_sameContentId_runsTasksInSubmissionOrder() throws InterruptedException {
        ThreadPoolTaskExecutor lane = newRealLane();
        ContentSearchKeyedExecutor executor = new ContentSearchKeyedExecutor(List.of(lane));
        UUID contentId = UUID.randomUUID();
        List<Integer> executionOrder = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            int taskIndex = i;
            executor.execute(contentId, () -> {
                executionOrder.add(taskIndex);
                latch.countDown();
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executionOrder).containsExactlyElementsOf(IntStream.range(0, 20).boxed().toList());
    }

    @Test
    @DisplayName("destroy()는 DisposableBean인 레인에는 destroy()를, 아니면 ExecutorService면 shutdown()을 호출한다")
    void destroy_disposesEachLaneAppropriately() throws Exception {
        FakeDisposableExecutor disposableLane = new FakeDisposableExecutor();
        ExecutorService plainExecutorService = mock(ExecutorService.class);
        Executor plainExecutor = task -> { };

        ContentSearchKeyedExecutor executor =
                new ContentSearchKeyedExecutor(List.of(disposableLane, plainExecutorService, plainExecutor));

        executor.destroy();

        assertThat(disposableLane.destroyed).isTrue();
        verify(plainExecutorService).shutdown();
    }

    private ThreadPoolTaskExecutor newRealLane() {
        ThreadPoolTaskExecutor lane = new ThreadPoolTaskExecutor();
        lane.setCorePoolSize(1);
        lane.setMaxPoolSize(1);
        lane.initialize();
        realLanes.add(lane);
        return lane;
    }

    private static class FakeDisposableExecutor implements Executor, DisposableBean {
        private boolean destroyed = false;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }
}
