package com.mopl.user.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * OAuthAccount 생성 시 필수 식별 정보 검증을 확인
 *
 * <p>DB 제약 조건에 도달하기 전에 애플리케이션 계층에서도
 * 잘못된 OAuth 연결 객체가 생성되지 않도록 검증합니다.</p>
 */
class OAuthAccountTest {

    @Test
    @DisplayName("연결할 사용자가 없으면 OAuth 계정을 생성할 수 없다")
    void create_fail_whenUserIsNull() {
        assertThatThrownBy(() ->
            OAuthAccount.builder()
                .user(null)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-user-id")
                .build()
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAuth 계정을 연결할 사용자는 필수입니다.");
    }

    @Test
    @DisplayName("OAuth Provider가 없으면 계정을 생성할 수 없다")
    void create_fail_whenProviderIsNull() {
        User user = createUser();

        assertThatThrownBy(() ->
            OAuthAccount.builder()
                .user(user)
                .provider(null)
                .providerUserId("provider-user-id")
                .build()
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAuth Provider는 필수입니다.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("Provider 사용자 ID가 없거나 공백이면 계정을 생성할 수 없다")
    void create_fail_whenProviderUserIdIsBlank(
        String providerUserId
    ) {
        User user = createUser();

        assertThatThrownBy(() ->
            OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.KAKAO)
                .providerUserId(providerUserId)
                .build()
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("OAuth Provider 사용자 ID는 필수입니다.");
    }

    @Test
    @DisplayName("Provider 사용자 ID가 255자를 초과하면 계정을 생성할 수 없다")
    void create_fail_whenProviderUserIdExceedsMaxLength() {
        User user = createUser();
        String tooLongProviderUserId = "a".repeat(256);

        assertThatThrownBy(() ->
            OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.NAVER)
                .providerUserId(tooLongProviderUserId)
                .build()
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth Provider 사용자 ID는 255자를 초과할 수 없습니다."
            );
    }

    /**
     * OAuth 계정 생성 검증에 사용할 사용자 객체를 만든다.
     *
     * <p>이 테스트는 JPA 저장이 아니라 OAuthAccount 생성자 검증이
     * 목적이므로 User를 데이터베이스에 저장하지 않습니다.</p>
     */
    private User createUser() {
        return User.builder()
            .email("oauth-user@example.com")
            .passwordHash(null)
            .name("OAuth 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}
