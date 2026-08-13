package com.mopl.watchingsession.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

/**
 * application-test.yml의 watching-session TTL 오버라이드가 실제로 바인딩되는지 확인한다.
 *
 * 이 값이 잘못 바인딩되면(프로퍼티 키 오타 등) 운영 기본값(60s/30m)이 그대로 적용되어
 * heartbeat E2E 3종이 전부 5초 타임아웃으로 실패한다. 그 원인을 즉시 짚어주기 위한 테스트.
 */
@SpringBootTest(classes = WatchingSessionPropertiesBindingTest.TestConfig.class)
@ActiveProfiles("test")
class WatchingSessionPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(WatchingSessionProperties.class)
    static class TestConfig {
    }

    @Autowired
    private WatchingSessionProperties properties;

    @Test
    @DisplayName("테스트 프로파일에서는 운영 기본값이 아니라 짧은 TTL로 오버라이드된다")
    void testProfile_overridesToShortTtl() {
        assertThat(properties.getPresenceTtl()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getSessionTtl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getHeartbeatInterval()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("presence-ttl이 빈 값으로 바인딩되면 애플리케이션 기동이 실패한다")
    void binding_fails_whenPresenceTtlIsMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                "watching-session.presence-ttl=",
                "watching-session.session-ttl=3s",
                "watching-session.heartbeat-interval=500ms"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
            });
    }
}
