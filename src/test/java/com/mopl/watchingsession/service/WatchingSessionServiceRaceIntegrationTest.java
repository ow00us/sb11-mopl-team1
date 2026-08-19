package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * WebSocket/STOMP 계층을 거치지 않고 WatchingSessionService를 직접 호출한다.
 * start()만 호출하고
 * WatchSubscriptionAttributes.activate()는 절대 호출하지 않은 채(=activate 호출 전 상태와 동치)
 * endByConnection()을 호출해, sessionId 단독 판정이 activate() 여부와 무관하게 올바르게
 * 동작함을 증명한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class WatchingSessionServiceRaceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    @Autowired
    private WatchingSessionService watchingSessionService;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchingSessionSnapshotRepository snapshotRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private UUID watcherId;
    private UUID contentId;
    private static final String SESSION_ID = "session-race-1";
    private static final String SUBSCRIPTION_ID = "sub-race-1";

    @AfterEach
    void tearDown() {
        snapshotRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
    }

    private void givenWatcherAndContent() {
        User watcher = userRepository.save(User.builder()
            .email("service-race-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("경합테스트유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        watcherId = watcher.getId();

        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("경합 테스트 콘텐츠")
            .description("설명")
            .build());
        contentId = content.getId();
    }

    private String presenceKey(UUID watcherId) {
        return "mopl:presence:watcher:" + watcherId;
    }

    @Test
    @DisplayName("start() 성공 직후, activate()를 전혀 호출하지 않은 상태에서도 "
        + "endByConnection()은 sessionId만으로 presence·DB 스냅샷을 정상 삭제하고 삭제 대상과 일치하는 DTO를 반환한다")
    void endByConnection_succeedsWithoutActivate_evenThoughStartJustCompleted() {
        // given: start()만 호출한다. 리스너의 activate() 호출은 의도적으로 생략한다 -
        // 이 상태가 곧 "presence 기록 직후, activate() 호출 전" 시점과 동치다.
        givenWatcherAndContent();
        watchingSessionService.start(watcherId, contentId, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(stringRedisTemplate.opsForHash().entries(presenceKey(watcherId))).isNotEmpty();
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent();

        // when: activate() 없이 곧바로 연결 종료 처리
        Optional<WatchingSessionDto> ended = watchingSessionService.endByConnection(watcherId, SESSION_ID);

        // then: sessionId만으로 정상 삭제되고, 삭제 대상과 일치하는 DTO가 반환된다
        assertThat(ended).isPresent();
        assertThat(ended.get().content().id()).isEqualTo(contentId);
        assertThat(ended.get().watcher().userId()).isEqualTo(watcherId);

        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty();
        assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isFalse();
    }

    @Test
    @DisplayName("같은 연결에서 재구독(sessionId 동일, subscriptionId만 변경)이 activate() 전에 "
        + "먼저 끝난 뒤 연결이 끊기면, 최신 구독(최신 콘텐츠)의 presence·DB가 삭제되고 그 콘텐츠 기준 DTO가 반환된다")
    void endByConnection_deletesLatestSubscription_whenResubscribeCompletesBeforeActivate() {
        // given: 같은 연결(sessionId 동일)에서 콘텐츠 A -> B로 재구독. 두 번 다 activate()는 호출하지 않는다.
        givenWatcherAndContent();
        Content contentB = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("두 번째 콘텐츠")
            .description("설명")
            .build());

        watchingSessionService.start(watcherId, contentId, SESSION_ID, "sub-A");
        watchingSessionService.start(watcherId, contentB.getId(), SESSION_ID, "sub-B");

        // when: 연결이 끊긴다. sessionId만 비교하므로 subscriptionId 이력과 무관하게
        // "현재" presence(콘텐츠 B)를 삭제해야 한다 - 이게 바로 리뷰에서 지적한 레이스를
        // endByConnection()의 반환값 기준 브로드캐스트로 막았는지 확인하는 지점이다.
        Optional<WatchingSessionDto> ended = watchingSessionService.endByConnection(watcherId, SESSION_ID);

        assertThat(ended).isPresent();
        assertThat(ended.get().content().id()).isEqualTo(contentB.getId()); // A가 아니라 B여야 함
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty();
        assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isFalse();
    }
}
