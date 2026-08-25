package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 운영 설정 형식 검증을 확인합니다.
 *
 * <p>값이 있는지는 바인딩이 봅니다. 여기서 보는 것은 값이 있어도 쓸 수 없는 형식인 경우이고,
 * 그것은 값을 넣어 봐야만 확인됩니다.
 */
class ProdEnvironmentValidatorTest {

    private static final Map<String, String> VALID = Map.of(
        "app.oauth2.redirect.success-uri", "https://mopl.example.com/oauth/callback",
        "app.oauth2.redirect.failure-uri", "https://mopl.example.com/sign-in",
        "spring.security.oauth2.client.registration.google.redirect-uri",
        "https://mopl.example.com/login/oauth2/code/google",
        "spring.security.oauth2.client.registration.kakao.redirect-uri",
        "https://mopl.example.com/login/oauth2/code/kakao",
        "spring.security.oauth2.client.registration.naver.redirect-uri",
        "https://mopl.example.com/login/oauth2/code/naver",
        "app.cors.allowed-origins", "https://mopl.example.com",
        "app.websocket.allowed-origins", "https://mopl.example.com");

    private static ProdEnvironmentValidator validatorWith(Map<String, String> overrides) {
        MockEnvironment environment = new MockEnvironment();
        VALID.forEach(environment::setProperty);
        overrides.forEach(environment::setProperty);
        return new ProdEnvironmentValidator(environment);
    }

    @Test
    @DisplayName("올바른 설정이면 통과한다")
    void passesWithValidConfiguration() {
        assertThatCode(() -> validatorWith(Map.of()).afterPropertiesSet())
            .doesNotThrowAnyException();
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
