package com.mopl.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

/**
 * JWT 설정값의 실제 ConfigurationProperties 바인딩과
 * 애플리케이션 시작 시점 검증을 확인
 *
 * <p>{@link JwtPropertiesTest}는 Validator를 직접 호출하여
 * 개별 제약 조건을 검증합니다. 이 테스트는 실제 Spring Context를
 * 실행하여 잘못된 jwt.* 설정이 애플리케이션 시작 실패로
 * 연결되는지 확인합니다.</p>
 *
 * <p>전체 애플리케이션을 실행하지 않고 JwtProperties 바인딩에
 * 필요한 최소 Context만 생성하므로 PostgreSQL, Redis, Kafka 등의
 * 외부 인프라는 필요하지 않습니다.</p>
 */
class JwtPropertiesBindingTest {

    /**
     * HS256 최소 요구사항을 충족하는 32바이트 Secret을
     * Base64로 인코딩한 테스트 설정값
     */
    private static final String VALID_SECRET =
        Base64.getEncoder()
            .encodeToString(
                new byte[32]
            );

    /**
     * ConfigurationProperties 바인딩 자동 설정과
     * JwtProperties만 포함하는 최소 Spring Context를 생성
     *
     * @return JWT 설정 바인딩 검증용 ApplicationContextRunner
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class
                )
            )
            .withUserConfiguration(
                JwtProperties.class
            );
    }

    @Test
    @DisplayName("유효한 JWT 설정은 정상적으로 바인딩되고 Context가 시작된다")
    void contextStarts_whenJwtPropertiesAreValid() {
        contextRunner()
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=" + VALID_SECRET,
                "jwt.access-token-expiration=30m"
            )
            .run(context -> {
                // Context가 정상적으로 시작됐는지 확인
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(JwtProperties.class);

                JwtProperties properties =
                    context.getBean(
                        JwtProperties.class
                    );

                /*
                 * 문자열과 Duration 설정이 JwtProperties에
                 * 의도한 값으로 바인딩됐는지 확인
                 */
                assertThat(properties.getIssuer())
                    .isEqualTo("mopl");

                assertThat(properties.getSecret())
                    .isEqualTo(VALID_SECRET);

                assertThat(
                    properties.getAccessTokenExpiration()
                ).isEqualTo(
                    Duration.ofMinutes(30)
                );
            });
    }

    @Test
    @DisplayName("JWT issuer가 누락되면 Context 시작에 실패한다")
    void contextFails_whenIssuerIsMissing() {
        contextRunner()
            /*
             * jwt.issuer를 의도적으로 전달하지 않는다.
             */
            .withPropertyValues(
                "jwt.secret=" + VALID_SECRET,
                "jwt.access-token-expiration=30m"
            )
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                /*
                 * 단순 Bean 생성 오류가 아니라
                 * ConfigurationProperties의 Bean Validation 실패가
                 * 원인인지 확인
                 */
                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("JWT issuer 앞뒤에 공백이 있으면 Context 시작에 실패한다")
    void contextFails_whenIssuerHasSurroundingWhitespace() {
        contextRunner()
            /*
             * withPropertyValues()는 테스트 프로퍼티 문자열을 파싱하면서
             * 값의 앞뒤 공백을 정리할 수 있다.
             *
             * MapPropertySource를 직접 추가하여 환경 변수나 외부 설정에
             * 실제 공백이 포함된 상황을 그대로 재현
             */
            .withInitializer(context -> {
                Map<String, Object> properties =
                    Map.of(
                        "jwt.issuer",
                        " mopl ",
                        "jwt.secret",
                        VALID_SECRET,
                        "jwt.access-token-expiration",
                        "30m"
                    );

                context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new MapPropertySource(
                            "jwtWhitespaceTestProperties",
                            properties
                        )
                    );
            })
            .run(context -> {
                assertThat(context)
                    .hasFailed();

                /*
                 * 공백이 보존된 issuer가 JwtProperties에 바인딩된 뒤
                 * isIssuerTrimmed() 검증에서 거부됐는지 확인
                 */
                assertThat(
                    context.getStartupFailure()
                ).hasRootCauseInstanceOf(
                    BindValidationException.class
                );
            });
    }

    @Test
    @DisplayName("JWT Secret이 누락되면 Context 시작에 실패한다")
    void contextFails_whenSecretIsMissing() {
        contextRunner()
            /*
             * JWT_SECRET 환경 변수가 누락된 배포 상황을 재현
             */
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.access-token-expiration=30m"
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
    @DisplayName("JWT Secret이 Base64 형식이 아니면 Context 시작에 실패한다")
    void contextFails_whenSecretIsNotBase64() {
        contextRunner()
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=not-valid-base64%%%",
                "jwt.access-token-expiration=30m"
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
    @DisplayName("JWT Secret이 32바이트보다 짧으면 Context 시작에 실패한다")
    void contextFails_whenDecodedSecretIsTooShort() {
        String shortSecret =
            Base64.getEncoder()
                .encodeToString(
                    new byte[31]
                );

        contextRunner()
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=" + shortSecret,
                "jwt.access-token-expiration=30m"
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
    @DisplayName("Access Token 만료 시간이 누락되면 Context 시작에 실패한다")
    void contextFails_whenAccessTokenExpirationIsMissing() {
        contextRunner()
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=" + VALID_SECRET
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
    @DisplayName("Access Token 만료 시간이 0이면 Context 시작에 실패한다")
    void contextFails_whenAccessTokenExpirationIsZero() {
        contextRunner()
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=" + VALID_SECRET,
                "jwt.access-token-expiration=0s"
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
    @DisplayName("Access Token 만료 시간이 소수 초이면 Context 시작에 실패한다")
    void contextFails_whenAccessTokenExpirationHasFractionalSecond() {
        contextRunner()
            /*
             * Spring Duration 바인딩에서 1500ms는 유효한 형식이지만
             * JWT 설정 정책상 정수 초가 아니므로 거부되어야 한다.
             */
            .withPropertyValues(
                "jwt.issuer=mopl",
                "jwt.secret=" + VALID_SECRET,
                "jwt.access-token-expiration=1500ms"
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
