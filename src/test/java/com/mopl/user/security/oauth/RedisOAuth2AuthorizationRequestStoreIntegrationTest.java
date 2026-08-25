package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 인가 요청 저장소의 Redis 동작을 실제 Redis 로 검증합니다.
 *
 * <p>보관 기간 만료와 원자적 소비는 실제 서버에서만 확인됩니다. 모킹하면 "삭제 메서드를
 * 호출했다"만 확인되고, 같은 state 로 동시에 들어온 두 요청 중 하나만 값을 받아 가는지는
 * 검증되지 않습니다.
 */
@SpringBootTest(classes = {
    RedisOAuth2AuthorizationRequestStore.class,
    RedisAutoConfiguration.class
})
@ActiveProfiles("test")
@Testcontainers
class RedisOAuth2AuthorizationRequestStoreIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static final String KEY = "auth:oauth2:authorization-request:state-abc";
    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    OAuth2AuthorizationRequestStore store;

    @Test
    @DisplayName("저장한 값을 읽는다")
    void savesAndFinds() {
        store.save(KEY, "stored-request", TTL);

        assertThat(store.find(KEY)).isEqualTo("stored-request");
    }

    @Test
    @DisplayName("저장하지 않은 키는 비어 있다")
    void findsNothingForUnknownKey() {
        assertThat(store.find("auth:oauth2:authorization-request:unknown")).isNull();
    }

    /**
     * 소비는 한 번만 성공해야 합니다. 읽기와 지우기가 나뉘면 같은 state 로 들어온 두 번째
     * 요청이 같은 인가 요청을 받아 갑니다.
     */
    @Test
    @DisplayName("한 번 꺼내면 다시 꺼낼 수 없다")
    void consumesOnce() {
        store.save(KEY, "stored-request", TTL);

        assertThat(store.findAndRemove(KEY)).isEqualTo("stored-request");
        assertThat(store.findAndRemove(KEY)).isNull();
        assertThat(store.find(KEY)).isNull();
    }

    /**
     * 사용자가 Provider 화면에서 로그인을 끝내지 않고 떠나면 그 요청은 영영 소비되지
     * 않습니다. 만료가 없으면 그런 항목이 계속 쌓입니다.
     */
    @Test
    @DisplayName("보관 기간이 지나면 사라진다")
    void expiresAfterTimeToLive() {
        store.save(KEY, "stored-request", Duration.ofSeconds(1));

        await().atMost(Duration.ofSeconds(10)).until(() -> store.find(KEY) == null);
    }
}
