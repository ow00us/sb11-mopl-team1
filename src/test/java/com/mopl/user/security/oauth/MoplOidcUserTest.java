package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.UserRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;

/**
 * OIDC 인증 결과를 모두의 플리 공통 Principal로 변환하는 동작을 검증
 */
class MoplOidcUserTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Test
    @DisplayName("OIDC Principal은 공통 사용자 정보와 OIDC 인증 정보를 제공한다")
    void create_success() {
        // given
        Instant issuedAt =
            Instant.parse("2026-08-21T00:00:00Z");

        Map<String, Object> claims = Map.of(
            "sub",
            "google-sub-123",
            "email",
            "user@example.com",
            "email_verified",
            true
        );

        OidcIdToken idToken =
            new OidcIdToken(
                "masked-test-id-token",
                issuedAt,
                issuedAt.plusSeconds(300),
                claims
            );

        OidcUserInfo userInfo =
            new OidcUserInfo(
                Map.of(
                    "sub",
                    "google-sub-123",
                    "email",
                    "user@example.com"
                )
            );

        // when
        MoplOidcUser oidcUser =
            new MoplOidcUser(
                USER_ID,
                "user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-sub-123",
                claims,
                idToken,
                userInfo
            );

        // then
        assertThat(oidcUser.getUserId())
            .isEqualTo(USER_ID);
        assertThat(oidcUser.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(oidcUser.getProviderUserId())
            .isEqualTo("google-sub-123");
        assertThat(oidcUser.getName())
            .isEqualTo("GOOGLE:google-sub-123");

        assertThat(oidcUser.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_USER");

        assertThat(oidcUser.getClaims())
            .containsEntry(
                "sub",
                "google-sub-123"
            );
        assertThat(oidcUser.getClaims())
            .containsEntry(
                "email_verified",
                true
            );

        assertThat(oidcUser.getIdToken())
            .isSameAs(idToken);
        assertThat(oidcUser.getUserInfo())
            .isSameAs(userInfo);

        /*
         * 기존 OAuth 성공 Handler가 사용하는 공통 Principal 타입과
         * 호환되는지 명시적으로 확인
         */
        assertThat(oidcUser)
            .isInstanceOf(MoplOAuth2User.class);
    }

    @Test
    @DisplayName("UserInfo가 없어도 OIDC Principal을 생성할 수 있다")
    void create_success_whenUserInfoIsNull() {
        // given
        Instant issuedAt =
            Instant.parse("2026-08-21T00:00:00Z");

        Map<String, Object> claims = Map.of(
            "sub",
            "google-sub-123",
            "email",
            "user@example.com"
        );

        OidcIdToken idToken =
            new OidcIdToken(
                "masked-test-id-token",
                issuedAt,
                issuedAt.plusSeconds(300),
                claims
            );

        // when
        MoplOidcUser oidcUser =
            new MoplOidcUser(
                USER_ID,
                "user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-sub-123",
                claims,
                idToken,
                null
            );

        // then
        assertThat(oidcUser.getUserInfo())
            .isNull();
    }

    @Test
    @DisplayName("ID Token이 없으면 OIDC Principal을 생성할 수 없다")
    void create_fail_whenIdTokenIsNull() {
        assertThatThrownBy(() ->
            new MoplOidcUser(
                USER_ID,
                "user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-sub-123",
                Map.of(
                    "sub",
                    "google-sub-123"
                ),
                null,
                null
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OIDC ID Token은 필수입니다."
            );
    }
}
