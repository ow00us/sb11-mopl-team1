package com.mopl.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OAuth 전용 사용자의 로컬 로그인 수단 등록 동작을 검증
 */
class UserLocalCredentialTest {

    @Test
    @DisplayName("OAuth 전용 사용자에게 실제 이메일과 비밀번호 해시를 등록한다")
    void registerLocalCredential_success() {
        // given
        User user =
            oauthOnlyUser();

        // when
        user.registerLocalCredential(
            "user@example.com",
            "encoded-password"
        );

        // then
        assertThat(user.getEmail())
            .isEqualTo(
                "user@example.com"
            );

        assertThat(user.getPasswordHash())
            .isEqualTo(
                "encoded-password"
            );
    }

    @Test
    @DisplayName("이미 로컬 비밀번호가 있는 사용자에게 다시 등록할 수 없다")
    void registerLocalCredential_rejectsExistingCredential() {
        // given
        User user =
            User.builder()
                .email(
                    "local@example.com"
                )
                .passwordHash(
                    "existing-password-hash"
                )
                .name("로컬 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        // when & then
        assertThatThrownBy(
            () ->
                user.registerLocalCredential(
                    "new@example.com",
                    "new-password-hash"
                )
        )
            .isInstanceOf(
                IllegalStateException.class
            )
            .hasMessage(
                "이미 로컬 로그인 수단이 등록되어 있습니다."
            );

        /*
         * 실패한 호출이 기존 이메일과 비밀번호를 일부 변경하지 않았는지 확인
         */
        assertThat(user.getEmail())
            .isEqualTo(
                "local@example.com"
            );

        assertThat(user.getPasswordHash())
            .isEqualTo(
                "existing-password-hash"
            );
    }

    @Test
    @DisplayName("등록 이메일이 공백이면 사용자 상태를 변경하지 않는다")
    void registerLocalCredential_rejectsBlankEmail() {
        // given
        User user =
            oauthOnlyUser();

        // when & then
        assertThatThrownBy(
            () ->
                user.registerLocalCredential(
                    " ",
                    "encoded-password"
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "로컬 로그인 이메일은 비어 있을 수 없습니다."
            );

        assertThat(user.getEmail())
            .endsWith(
                "@oauth.invalid"
            );

        assertThat(user.getPasswordHash())
            .isNull();
    }

    @Test
    @DisplayName("비밀번호 해시가 공백이면 사용자 상태를 변경하지 않는다")
    void registerLocalCredential_rejectsBlankPasswordHash() {
        // given
        User user =
            oauthOnlyUser();

        // when & then
        assertThatThrownBy(
            () ->
                user.registerLocalCredential(
                    "user@example.com",
                    " "
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "로컬 로그인 비밀번호 해시는 비어 있을 수 없습니다."
            );

        assertThat(user.getEmail())
            .endsWith(
                "@oauth.invalid"
            );

        assertThat(user.getPasswordHash())
            .isNull();
    }

    private User oauthOnlyUser() {
        return User.builder()
            .email(
                "google-user@oauth.invalid"
            )
            .passwordHash(null)
            .name("OAuth 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}
