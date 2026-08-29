package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.user.config.OAuthRedirectProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * 운영 설정 형식 검증을 확인합니다.
 *
 * <p>값이 있는지는 바인딩이 봅니다. 여기서 보는 것은 값이 있어도 쓸 수 없는 형식인 경우이고,
 * 그것은 값을 넣어 봐야만 확인됩니다.
 */
class ProdEnvironmentValidatorTest {

    private static final Map<String, String> VALID = Map.ofEntries(
        Map.entry("app.oauth2.redirect.success-uri", "https://mopl.example.com/oauth/callback"),
        Map.entry("app.oauth2.redirect.failure-uri", "https://mopl.example.com/sign-in"),
        Map.entry("spring.security.oauth2.client.registration.google.redirect-uri",
            "https://mopl.example.com/login/oauth2/code/google"),
        Map.entry("spring.security.oauth2.client.registration.kakao.redirect-uri",
            "https://mopl.example.com/login/oauth2/code/kakao"),
        Map.entry("spring.security.oauth2.client.registration.naver.redirect-uri",
            "https://mopl.example.com/login/oauth2/code/naver"),
        Map.entry("app.cors.allowed-origins", "https://mopl.example.com"),
        Map.entry("app.websocket.allowed-origins", "https://mopl.example.com"),
        Map.entry("mopl.storage.image.bucket", "sb11-mopl-team1-images"),
        Map.entry("mopl.storage.image.public-base-url",
            "https://sb11-mopl-team1-images.s3.ap-northeast-2.amazonaws.com"));

    private static ProdEnvironmentValidator validatorWith(Map<String, String> overrides) {
        MockEnvironment environment = new MockEnvironment();
        propertiesWith(overrides).forEach((key, value) ->
            environment.setProperty(key, (String) value));
        return new ProdEnvironmentValidator(environment);
    }

    private static Map<String, Object> propertiesWith(Map<String, String> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>(VALID);
        overrides.forEach((key, value) -> {
            if (value == null) {
                properties.remove(key);
            } else {
                properties.put(key, value);
            }
        });
        return properties;
    }

    private ApplicationContextRunner contextRunner(Map<String, String> overrides) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ProdEnvironmentValidator.class)
            .withInitializer(context -> {
                context.getEnvironment().setActiveProfiles("prod");
                context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("prod-validation-test", propertiesWith(overrides)));
            });
    }

    private static Map<String, String> override(String key, String value) {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(key, value);
        return overrides;
    }

    private static Stream<Arguments> invalidUris() {
        return Stream.of(
            "app.oauth2.redirect.success-uri",
            "app.oauth2.redirect.failure-uri",
            "spring.security.oauth2.client.registration.google.redirect-uri",
            "spring.security.oauth2.client.registration.kakao.redirect-uri",
            "spring.security.oauth2.client.registration.naver.redirect-uri",
            "app.cors.allowed-origins",
            "app.websocket.allowed-origins",
            "mopl.storage.image.public-base-url")
            .flatMap(key -> Stream.of(
                Arguments.of(key, "https://bad host.example.test"),
                Arguments.of(key, "/relative-path"),
                Arguments.of(key, "https:/missing-host")));
    }

    private static Stream<Arguments> missingImageTargets() {
        return Stream.of("mopl.storage.image.bucket", "mopl.storage.image.public-base-url")
            .flatMap(key -> Stream.of(
                Arguments.of(key, null), Arguments.of(key, ""), Arguments.of(key, " \t")));
    }

    private static Stream<Arguments> absentRedirects() {
        return Stream.of("app.oauth2.redirect.success-uri", "app.oauth2.redirect.failure-uri")
            .flatMap(key -> Stream.of(
                Arguments.of(key, null), Arguments.of(key, ""), Arguments.of(key, " \t")));
    }

    private static Stream<Arguments> absentOrigins() {
        return Stream.of("app.cors.allowed-origins", "app.websocket.allowed-origins")
            .flatMap(key -> Stream.of(
                Arguments.of(key, null), Arguments.of(key, ""), Arguments.of(key, " \t")));
    }

    @Test
    @DisplayName("올바른 설정이면 통과한다")
    void passesWithValidConfiguration() {
        assertThatCode(() -> validatorWith(Map.of()).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("invalidUris")
    @DisplayName("URI 문법 오류·상대 URI·host 없는 절대 URI는 각 설정의 기동 단계에서 거부한다")
    void invalidUriPreventsProductionContextStartup(String key, String invalidUri) {
        contextRunner(Map.of(key, invalidUri)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(key)
                .hasStackTraceContaining("scheme 과 host");
        });
    }

    @Test
    @DisplayName("정상 절대 URI와 포트가 있는 복수 origin은 운영 Context를 시작한다")
    void validUrisAllowProductionContextStartup() {
        contextRunner(Map.of(
            "app.cors.allowed-origins", "https://mopl.example.com, https://admin.example.test:8443",
            "app.websocket.allowed-origins", "https://mopl.example.com, http://127.0.0.1:5173",
            "mopl.storage.image.public-base-url", "https://cdn.example.test/images/"))
            .run(context -> assertThat(context)
                .hasNotFailed().hasSingleBean(ProdEnvironmentValidator.class));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("absentRedirects")
    @DisplayName("누락된 OAuth redirect는 형식 검사에서 중복 보고하지 않고 실제 바인딩이 거부한다")
    void missingRedirectIsRejectedByBindingRatherThanFormatCheck(String key, String value) {
        Map<String, String> overrides = override(key, value);
        assertThatCode(() -> validatorWith(overrides).afterPropertiesSet())
            .doesNotThrowAnyException();

        contextRunner(overrides).withUserConfiguration(OAuthRedirectProperties.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("absentOrigins")
    @DisplayName("origin 입력이 없거나 공백이어도 나머지 설정 오류 검사를 계속한다")
    void absentOriginDoesNotPreventReportingOtherConfigurationErrors(String key, String value) {
        Map<String, String> overrides = override(key, value);
        overrides.put("mopl.storage.image.bucket", "");

        contextRunner(overrides).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("mopl.storage.image.bucket 가 비어 있습니다");
        });
    }

    /**
     * 상대 경로를 넣으면 Provider 가 돌아올 곳을 찾지 못합니다. 그 사실은 로그인 시도가 있을
     * 때까지 드러나지 않습니다.
     */
    @Test
    @DisplayName("OAuth callback URI가 절대 URI가 아니면 기동을 막는다")
    void rejectsRelativeCallbackUri() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "spring.security.oauth2.client.registration.google.redirect-uri",
            "/login/oauth2/code/google")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("google.redirect-uri");
    }

    @Test
    @DisplayName("성공·실패 redirect URI가 절대 URI가 아니면 기동을 막는다")
    void rejectsRelativeRedirectUri() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "app.oauth2.redirect.success-uri", "/oauth/callback")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("success-uri");
    }

    /**
     * origin 은 scheme 과 host 까지입니다. 경로가 붙으면 어떤 요청과도 맞지 않아, 설정은
     * 있는데 모든 브라우저 요청이 막힙니다.
     */
    @Test
    @DisplayName("origin에 경로가 붙어 있으면 기동을 막는다")
    void rejectsOriginWithPath() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "app.cors.allowed-origins", "https://mopl.example.com/app")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("경로가 붙어");
    }

    @Test
    @DisplayName("origin에 scheme이 없으면 기동을 막는다")
    void rejectsOriginWithoutScheme() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "app.websocket.allowed-origins", "mopl.example.com")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("websocket");
    }

    @Test
    @DisplayName("origin 목록에 빈 항목이 있으면 기동을 막는다")
    void rejectsEmptyOriginEntry() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "app.cors.allowed-origins", "https://mopl.example.com,,https://admin.mopl.example.com"))
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("빈 항목");
    }

    @Test
    @DisplayName("origin을 여러 개 주면 각각을 확인한다")
    void validatesEveryOrigin() {
        assertThatCode(() -> validatorWith(Map.of(
            "app.cors.allowed-origins",
            "https://mopl.example.com, https://admin.mopl.example.com")).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    /**
     * 바인딩은 풀리지 않은 {@code ${...}} 를 문자열 그대로 넣습니다. 비어 있는지만 보는 검사는
     * 통과하므로, 존재하지 않는 버킷 이름을 들고 기동합니다.
     */
    @Test
    @DisplayName("이미지 저장소를 켠 채로 버킷이 비면 기동을 막는다")
    void rejectsEnabledImageStorageWithoutBucket() {
        assertThatThrownBy(() -> validatorWith(Map.of("mopl.storage.image.bucket", " "))
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mopl.storage.image.bucket");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("missingImageTargets")
    @DisplayName("운영 기본값으로 켜진 이미지 저장소는 대상의 누락·빈값·공백을 기동 시 거부한다")
    void missingImageTargetPreventsProductionContextStartup(String key, String value) {
        contextRunner(override(key, value)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining(key + " 가 비어 있습니다");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mopl.storage.image.bucket", "mopl.storage.image.public-base-url"})
    @DisplayName("이미 보고한 이미지 설정 placeholder 오류를 빈값 오류로 중복 보고하지 않는다")
    void unresolvedImagePlaceholderIsReportedOnce(String key) {
        assertThatThrownBy(() -> validatorWith(Map.of(key, "${TEST_UNRESOLVED_IMAGE_TARGET}"))
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .satisfies(thrown -> {
                List<String> problems = thrown.getMessage().lines()
                    .filter(line -> line.startsWith("- ")).toList();
                assertThat(problems).hasSize(1);
                assertThat(problems.get(0)).contains(key, "환경 변수가 없습니다")
                    .doesNotContain("가 비어 있습니다");
            });
    }

    @Test
    @DisplayName("이미지 저장소 조회 주소가 절대 URI가 아니면 기동을 막는다")
    void rejectsRelativeImageStoragePublicBaseUrl() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "mopl.storage.image.public-base-url", "/images")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mopl.storage.image.public-base-url");
    }

    /** 명시적으로 끈 경우에는 저장할 곳이 없어도 정상입니다. */
    @Test
    @DisplayName("이미지 저장소를 끄면 버킷이 없어도 통과한다")
    void allowsMissingBucketWhenImageStorageDisabled() {
        assertThatCode(() -> validatorWith(Map.of(
            "mopl.storage.image.enabled", "false",
            "mopl.storage.image.bucket", "",
            "mopl.storage.image.public-base-url", "")).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미지 저장소를 명시적으로 끄면 해석할 수 없는 대상 설정도 읽지 않는다")
    void disabledImageStorageDoesNotResolveUnusedTargetPlaceholders() {
        contextRunner(Map.of(
            "mopl.storage.image.enabled", "false",
            "mopl.storage.image.bucket", "${TEST_UNUSED_IMAGE_BUCKET}",
            "mopl.storage.image.public-base-url", "${TEST_UNUSED_IMAGE_URL}"))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.cors.allowed-origins", "app.websocket.allowed-origins"})
    @DisplayName("origin의 미해석 placeholder도 다른 설정 오류와 함께 보고한다")
    void unresolvedOriginIsAggregatedWithOtherProblems(String key) {
        assertThatThrownBy(() -> validatorWith(Map.of(
            key, "${TEST_UNRESOLVED_ORIGIN}",
            "mopl.storage.image.public-base-url", "/images")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .satisfies(thrown -> {
                List<String> problems = thrown.getMessage().lines()
                    .filter(line -> line.startsWith("- ")).toList();
                assertThat(problems).hasSize(2);
                assertThat(thrown.getMessage())
                    .contains(key, "환경 변수가 없습니다", "mopl.storage.image.public-base-url");
            });
    }

    /**
     * {@code Environment} 는 풀리지 않은 자리표시자를 만나면 그 자리에서 던집니다. 그대로 두면
     * 뒤에 있는 값은 보지도 못하고, 운영자가 한 번에 하나씩 고치며 다시 띄우게 됩니다.
     */
    @Test
    @DisplayName("환경 변수가 없어 자리표시자가 남으면 다른 문제와 함께 보고한다")
    void reportsUnresolvedPlaceholderAlongsideOtherProblems() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "spring.security.oauth2.client.registration.google.redirect-uri",
            "${GOOGLE_OAUTH_REDIRECT_URI}",
            "app.cors.allowed-origins", "mopl.example.com"))
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .satisfies(thrown -> assertThat(thrown.getMessage())
                .contains("google.redirect-uri")
                .contains("cors.allowed-origins"));
    }

    /**
     * 하나씩 던지면 운영자가 고치고 다시 띄우기를 값의 수만큼 반복해야 합니다. 배포 중에 그
     * 반복은 그대로 중단 시간입니다.
     */
    @Test
    @DisplayName("문제가 여럿이면 한 번에 모두 보고한다")
    void reportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> validatorWith(Map.of(
            "app.oauth2.redirect.success-uri", "/oauth/callback",
            "app.cors.allowed-origins", "mopl.example.com",
            "app.websocket.allowed-origins", "https://mopl.example.com/ws"))
            .afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .satisfies(thrown -> assertThat(thrown.getMessage())
                .contains("success-uri")
                .contains("cors.allowed-origins")
                .contains("websocket.allowed-origins"));
    }
}
