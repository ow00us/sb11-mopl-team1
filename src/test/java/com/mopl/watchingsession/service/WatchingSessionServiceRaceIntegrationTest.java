package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
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
    private WatchingSessionPresenceWriter watchingSessionPresenceWriter;

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

    @Test
    @DisplayName("정상 end()는 현재 세대 토큰과 일치해 행을 삭제하고 LEAVE 대상 DTO를 반환한다")
    void end_deletesCurrentGeneration_whenTokenMatches() {
        givenWatcherAndContent();
        watchingSessionService.start(watcherId, contentId, SESSION_ID, SUBSCRIPTION_ID);

        boolean ended = watchingSessionService.end(watcherId, SESSION_ID, SUBSCRIPTION_ID);

        assertThat(ended).isTrue();
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty();
    }

    @Test
    @DisplayName("start() 이후 presence에 기록된 세대 토큰은 DB의 updatedAt과 문자열 비교까지 정확히 일치한다")
    void start_presenceGenerationToken_roundTripsExactlyWithDbUpdatedAt() {
        givenWatcherAndContent();

        watchingSessionService.start(watcherId, contentId, SESSION_ID, SUBSCRIPTION_ID);

        Instant dbUpdatedAt = snapshotRepository.findByWatcherId(watcherId).orElseThrow().getUpdatedAt();
        // presence Redis Hash를 직접 조회해 저장된 문자열이 DB 값과 정확히 같은지 확인
        String presenceToken = stringRedisTemplate.opsForHash()
            .get("mopl:presence:watcher:" + watcherId, "snapshotUpdatedAt").toString();

        assertThat(Instant.parse(presenceToken)).isEqualTo(dbUpdatedAt);
    }

    @Test
    @DisplayName("presence 소유권 확인 직후 다른 인스턴스의 재구독이 끼어들어도 end()는 false를 반환하고 새 세대 행은 살아남는다")
    void end_returnsFalse_andPreservesNewGeneration_whenResubscribeRacesBetweenPresenceCheckAndDbDelete() {
        givenWatcherAndContent();
        watchingSessionService.start(watcherId, contentId, SESSION_ID, SUBSCRIPTION_ID);
        UUID snapshotId = snapshotRepository.findByWatcherId(watcherId).orElseThrow().getId();

        // end()가 presence 소유권 확인(deleteIfOwner)을 마친 직후, DB 삭제를 실행하기 전 사이에
        // 다른 인스턴스의 재구독이 끼어드는 좁은 시간창을 결정적으로 재현하기 위해 spy로 개입한다.
        WatchingSessionPresenceWriter realWriter = watchingSessionPresenceWriter;
        WatchingSessionPresenceWriter spyWriter = Mockito.spy(realWriter);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod(); // 실제 소유권 확인·삭제는 그대로 수행
            // 소유권 확인이 끝난 이 시점에 동일 콘텐츠로 재구독 - 세대 2, DB 행 refresh
            watchingSessionService.start(watcherId, contentId, SESSION_ID, "sub-race");
            return result;
        }).when(spyWriter).deleteIfOwner(eq(watcherId), eq(SESSION_ID), eq(SUBSCRIPTION_ID));

        ReflectionTestUtils.setField(watchingSessionService, "watchingSessionPresenceWriter", spyWriter);
        try {
            boolean ended = watchingSessionService.end(watcherId, SESSION_ID, SUBSCRIPTION_ID);

            // end()가 확보한 토큰은 세대 1의 것이라 세대 2로 바뀐 DB 행 삭제 조건과 불일치 -> 0행 삭제 -> false
            assertThat(ended).isFalse();
            assertThat(snapshotRepository.findById(snapshotId)).isPresent(); // 세대 2 행 생존
        } finally {
            ReflectionTestUtils.setField(watchingSessionService, "watchingSessionPresenceWriter", realWriter);
        }
    }
}
