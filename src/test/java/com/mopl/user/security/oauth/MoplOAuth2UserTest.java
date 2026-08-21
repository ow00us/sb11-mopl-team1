package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.UserRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Provider별 OAuth 사용자 정보를 모두의 플리 공통 인증 정보로
 * 변환하는 MoplOAuth2User의 동작을 검증
 */
class MoplOAuth2UserTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    /**
     * OAuth 인증 이후 성공 Handler가 필요한 사용자 정보와 권한을
     * Principal에서 조회할 수 있는지 확인
     */
    @Test
    @DisplayName("OAuth 공통 Principal은 사용자 정보와 권한을 제공한다")
    void create_success() {
        // given
        Map<String, Object> attributes = Map.of(
            "sub",
            "google-user-123",
            "email",
            "oauth-user@example.com"
        );

        // when
        MoplOAuth2User oauth2User =
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-user-123",
                attributes
            );

        // then
        assertThat(oauth2User.getUserId())
            .isEqualTo(USER_ID);
        assertThat(oauth2User.getEmail())
            .isEqualTo("oauth-user@example.com");
        assertThat(oauth2User.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(oauth2User.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(oauth2User.getProviderUserId())
            .isEqualTo("google-user-123");

        assertThat(oauth2User.getName())
            .isEqualTo("GOOGLE:google-user-123");

        assertThat(oauth2User.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_USER");

        assertThat(oauth2User.getAttributes())
            .containsEntry(
                "sub",
                "google-user-123"
            );
    }

    /**
     * ADMIN 사용자가 OAuth로 인증되더라도 기존 인가 정책에서 사용하는
     * ROLE_ADMIN 권한을 유지하는지 확인
     */
    @Test
    @DisplayName("관리자 OAuth 사용자는 ROLE_ADMIN 권한을 가진다")
    void create_adminAuthority() {
        // when
        MoplOAuth2User oauth2User =
            new MoplOAuth2User(
                USER_ID,
                "admin@example.com",
                UserRole.ADMIN,
                OAuthProvider.NAVER,
                "naver-admin-id",
                Map.of(
                    "id",
                    "naver-admin-id"
                )
            );

        // then
        assertThat(oauth2User.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_ADMIN");
    }

    /**
     * 생성 이후 원본 Map이 변경되어도 인증 Principal의 attributes가
     * 함께 변경되지 않는지 확인
     */
    @Test
    @DisplayName("OAuth 사용자 attributes는 방어적으로 복사된다")
    void attributes_areDefensivelyCopied() {
        // given
        Map<String, Object> mutableAttributes =
            new LinkedHashMap<>();

        mutableAttributes.put(
            "id",
            "kakao-user-123"
        );

        /*
         * 일부 Provider 응답에는 null 값이 포함될 수 있으므로
         * null 값이 있어도 Principal을 생성할 수 있어야 한다.
         */
        mutableAttributes.put(
            "optional_attribute",
            null
        );

        MoplOAuth2User oauth2User =
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                UserRole.USER,
                OAuthProvider.KAKAO,
                "kakao-user-123",
                mutableAttributes
            );

        // when
        mutableAttributes.put(
            "id",
            "changed-user-id"
        );

        mutableAttributes.put(
            "new_attribute",
            "unexpected-value"
        );

        // then
        assertThat(oauth2User.getAttributes())
            .containsEntry(
                "id",
                "kakao-user-123"
            );

        assertThat(oauth2User.getAttributes())
            .containsEntry(
                "optional_attribute",
                null
            );

        assertThat(oauth2User.getAttributes())
            .doesNotContainKey("new_attribute");
    }

    /**
     * Principal이 반환한 attributes를 외부 코드에서 수정할 수 없는지 확인
     */
    @Test
    @DisplayName("OAuth 사용자 attributes는 외부에서 수정할 수 없다")
    void attributes_areUnmodifiable() {
        // given
        MoplOAuth2User oauth2User =
            createValidOAuth2User();

        // when & then
        assertThatThrownBy(() ->
            oauth2User.getAttributes().put(
                "unexpected",
                "value"
            )
        )
            .isInstanceOf(
                UnsupportedOperationException.class
            );
    }

    @Test
    @DisplayName("사용자 UUID가 없으면 OAuth Principal을 생성할 수 없다")
    void create_fail_whenUserIdIsNull() {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                null,
                "oauth-user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-user-123",
                Map.of()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth 인증 사용자 UUID는 필수입니다."
            );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("이메일이 없거나 공백이면 OAuth Principal을 생성할 수 없다")
    void create_fail_whenEmailIsBlank(
        String email
    ) {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                USER_ID,
                email,
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-user-123",
                Map.of()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth 인증 사용자 이메일은 필수입니다."
            );
    }

    @Test
    @DisplayName("사용자 권한이 없으면 OAuth Principal을 생성할 수 없다")
    void create_fail_whenRoleIsNull() {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                null,
                OAuthProvider.GOOGLE,
                "google-user-123",
                Map.of()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth 인증 사용자 권한은 필수입니다."
            );
    }

    @Test
    @DisplayName("Provider가 없으면 OAuth Principal을 생성할 수 없다")
    void create_fail_whenProviderIsNull() {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                UserRole.USER,
                null,
                "provider-user-123",
                Map.of()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth 인증 Provider는 필수입니다."
            );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("Provider 사용자 ID가 없거나 공백이면 Principal을 생성할 수 없다")
    void create_fail_whenProviderUserIdIsBlank(
        String providerUserId
    ) {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                providerUserId,
                Map.of()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth Provider 사용자 ID는 필수입니다."
            );
    }

    @Test
    @DisplayName("attributes가 없으면 OAuth Principal을 생성할 수 없다")
    void create_fail_whenAttributesAreNull() {
        assertThatThrownBy(() ->
            new MoplOAuth2User(
                USER_ID,
                "oauth-user@example.com",
                UserRole.USER,
                OAuthProvider.GOOGLE,
                "google-user-123",
                null
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "OAuth 사용자 attributes는 필수입니다."
            );
    }

    /**
     * attributes 변경 불가 테스트에서 사용할 정상 Principal을 생성
     */
    private MoplOAuth2User createValidOAuth2User() {
        return new MoplOAuth2User(
            USER_ID,
            "oauth-user@example.com",
            UserRole.USER,
            OAuthProvider.GOOGLE,
            "google-user-123",
            Map.of(
                "sub",
                "google-user-123"
            )
        );
    }
}
