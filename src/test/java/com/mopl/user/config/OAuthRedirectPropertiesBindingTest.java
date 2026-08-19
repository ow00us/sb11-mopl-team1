package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * OAuth Redirect URI 설정의 실제 ConfigurationProperties 바인딩과
 * 애플리케이션 시작 시점 검증을 확인
 *
 * <p>OAuthRedirectPropertiesTest는 Validator를 직접 호출하여
 * 개별 URI 검증 조건을 확인합니다.</p>
 *
 * <p>이 테스트는 최소 Spring Context를 실행하여
 * 잘못된 app.oauth2.redirect 설정이 실제 Context 시작 실패로
 * 연결되는지 확인합니다.</p>
 */
class OAuthRedirectPropertiesBindingTest {

    /**
     * ConfigurationProperties 바인딩 자동 설정과
     * OAuthRedirectProperties만 포함하는 최소 Context를 생성
     *
     * <p>PostgreSQL, Redis, Kafka 등의 외부 인프라는 사용하지 않습니다.</p>
     *
     * @return OAuth Redirect 설정 바인딩 검증용 Context Runner
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class
                )
            )
            .withUserConfiguration(
                OAuthRedirectProperties.class
            );
    }

    @Test
    @DisplayName("유효한 OAuth Redirect 설정은 바인딩되고 Context가 시작된다")
    void contextStarts_whenRedirectUrisAreValid() {
        contextRunner()
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "http://localhost:5173/oauth/callback",
                "app.oauth2.redirect.failure-uri="
                    + "http://localhost:5173/sign-in"
            )
            .run(context -> {
                // Context가 정상적으로 시작됐는지 확인
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(
                        OAuthRedirectProperties.class
                    );

                OAuthRedirectProperties properties =
                    context.getBean(
                        OAuthRedirectProperties.class
                    );

                /*
                 * 문자열 설정이 URI 타입으로 정상 변환됐는지 확인
                 */
                assertThat(properties.getSuccessUri())
                    .isEqualTo(
                        URI.create(
                            "http://localhost:5173/oauth/callback"
                        )
                    );

                assertThat(properties.getFailureUri())
                    .isEqualTo(
                        URI.create(
                            "http://localhost:5173/sign-in"
                        )
                    );
            });
    }

    @Test
    @DisplayName("OAuth 성공 Redirect URI가 누락되면 Context 시작에 실패한다")
    void contextFails_whenSuccessUriIsMissing() {
        contextRunner()
            /*
             * success-uri를 의도적으로 전달하지 않아
             * 운영 환경 변수 누락 상황을 재현
             */
            .withPropertyValues(
                "app.oauth2.redirect.failure-uri="
                    + "https://mopl.example.com/sign-in"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("OAuth 실패 Redirect URI가 누락되면 Context 시작에 실패한다")
    void contextFails_whenFailureUriIsMissing() {
        contextRunner()
            /*
             * failure-uri를 의도적으로 전달하지 않아
             * 운영 환경 변수 누락 상황을 재현
             */
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "https://mopl.example.com/oauth/callback"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("상대 경로인 OAuth 성공 Redirect URI는 Context 시작에 실패한다")
    void contextFails_whenSuccessUriIsRelative() {
        contextRunner()
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "/oauth/callback",
                "app.oauth2.redirect.failure-uri="
                    + "https://mopl.example.com/sign-in"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("지원하지 않는 스킴의 실패 Redirect URI는 Context 시작에 실패한다")
    void contextFails_whenFailureUriSchemeIsUnsupported() {
        contextRunner()
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "https://mopl.example.com/oauth/callback",
                "app.oauth2.redirect.failure-uri="
                    + "javascript:alert"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("Query가 포함된 OAuth 성공 Redirect URI는 Context 시작에 실패한다")
    void contextFails_whenSuccessUriContainsQuery() {
        contextRunner()
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "https://mopl.example.com/oauth/callback?token=value",
                "app.oauth2.redirect.failure-uri="
                    + "https://mopl.example.com/sign-in"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("Fragment가 포함된 OAuth 실패 Redirect URI는 Context 시작에 실패한다")
    void contextFails_whenFailureUriContainsFragment() {
        contextRunner()
            .withPropertyValues(
                "app.oauth2.redirect.success-uri="
                    + "https://mopl.example.com/oauth/callback",
                "app.oauth2.redirect.failure-uri="
                    + "https://mopl.example.com/sign-in#error"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }
}
