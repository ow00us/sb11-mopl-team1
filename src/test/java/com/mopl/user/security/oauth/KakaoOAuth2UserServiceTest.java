package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.service.OAuthUserProvisioningService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Kakao OAuth2 사용자 정보를 MOPL 공통 Principal로 변환하는
 * 정상 흐름과 보안 실패 흐름을 검증
 */
@ExtendWith(MockitoExtension.class)
class KakaoOAuth2UserServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Mock
    OAuthUserProvisioningService provisioningService;

    @Mock
    OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    @Mock
    OAuth2UserRequest userRequest;

    @Mock
    ClientRegistration clientRegistration;

    @Mock
    OAuth2User kakaoUser;

    @Mock
    User user;

    KakaoOAuth2UserService kakaoOAuth2UserService;

    @BeforeEach
    void setUp() {
        kakaoOAuth2UserService =
            new KakaoOAuth2UserService(
                provisioningService,
                delegate
            );
    }

    @Test
    @DisplayName("Kakao 사용자는 MOPL OAuth2 Principal로 변환된다")
    void loadUser_success() {
        // given
        stubKakaoRequest();

        Map<String, Object> attributes =
            Map.of(
                "id",
                123456789L,
                "properties",
                Map.of(
                    "nickname",
                    " Kakao 사용자 ",
                    "profile_image",
                    " https://example.com/kakao-profile.png "
                )
            );

        when(delegate.loadUser(userRequest))
            .thenReturn(kakaoUser);

        when(kakaoUser.getAttributes())
            .thenReturn(attributes);

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                "https://example.com/kakao-profile.png"
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        OAuth2User result =
            kakaoOAuth2UserService.loadUser(
                userRequest
            );

        // then
        assertThat(result)
            .isInstanceOf(MoplOAuth2User.class);

        MoplOAuth2User moplUser =
            (MoplOAuth2User) result;

        assertThat(moplUser.getUserId())
            .isEqualTo(USER_ID);
        assertThat(moplUser.getEmail())
            .isEqualTo("kakao-user@oauth.invalid");
        assertThat(moplUser.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(moplUser.getProvider())
            .isEqualTo(OAuthProvider.KAKAO);
        assertThat(moplUser.getProviderUserId())
            .isEqualTo("123456789");
        assertThat(moplUser.getName())
            .isEqualTo("KAKAO:123456789");
        assertThat(moplUser.getAttributes())
            .isEqualTo(attributes);

        verify(delegate)
            .loadUser(userRequest);

        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                "https://example.com/kakao-profile.png"
            );
    }

    @Test
    @DisplayName("properties가 없으면 kakao_account profile을 사용한다")
    void loadUser_usesKakaoAccountProfile() {
        // given
        stubKakaoRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(kakaoUser);

        when(kakaoUser.getAttributes())
            .thenReturn(
                Map.of(
                    "id",
                    123456789L,
                    "kakao_account",
                    Map.of(
                        "profile",
                        Map.of(
                            "nickname",
                            "계정 프로필 사용자",
                            "profile_image_url",
                            "https://example.com/account-profile.png"
                        )
                    )
                )
            );

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "계정 프로필 사용자",
                "https://example.com/account-profile.png"
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        kakaoOAuth2UserService.loadUser(
            userRequest
        );

        // then
        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "계정 프로필 사용자",
                "https://example.com/account-profile.png"
            );
    }

    @Test
    @DisplayName("닉네임과 프로필 이미지가 없으면 기본 이름과 null을 사용한다")
    void loadUser_usesDefaultsForOptionalProfile() {
        // given
        stubKakaoRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(kakaoUser);

        when(kakaoUser.getAttributes())
            .thenReturn(
                Map.of(
                    "id",
                    123456789L
                )
            );

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                null
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        kakaoOAuth2UserService.loadUser(
            userRequest
        );

        // then
        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                null
            );
    }

    @Test
    @DisplayName("Kakao 사용자 ID가 없으면 사용자 조회 전에 인증에 실패한다")
    void loadUser_fail_whenUserIdIsMissing() {
        // given
        stubKakaoRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(kakaoUser);

        when(kakaoUser.getAttributes())
            .thenReturn(
                Map.of(
                    "properties",
                    Map.of(
                        "nickname",
                        "Kakao 사용자"
                    )
                )
            );

        // when & then
        assertThatThrownBy(() ->
            kakaoOAuth2UserService.loadUser(
                userRequest
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException oauthException =
                    (OAuth2AuthenticationException) exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    KakaoOAuth2UserService
                        .INVALID_KAKAO_USER_ID
                );
            });

        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("잠긴 MOPL 사용자는 Kakao 인증에도 성공할 수 없다")
    void loadUser_fail_whenAccountIsLocked() {
        // given
        stubKakaoRequest();
        stubValidKakaoUser();

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                "https://example.com/kakao-profile.png"
            )
        ).thenReturn(user);

        when(user.isLocked())
            .thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
            kakaoOAuth2UserService.loadUser(
                userRequest
            )
        )
            .isInstanceOf(LockedException.class)
            .hasMessage(
                KakaoOAuth2UserService
                    .KAKAO_ACCOUNT_LOCKED
            );
    }

    @Test
    @DisplayName("Kakao가 아닌 OAuth2 요청은 delegate 호출 전에 거부한다")
    void loadUser_fail_whenProviderIsNotKakao() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("naver");

        // when & then
        assertThatThrownBy(() ->
            kakaoOAuth2UserService.loadUser(
                userRequest
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException oauthException =
                    (OAuth2AuthenticationException) exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    "unsupported_oauth_provider"
                );
            });

        verify(delegate, never())
            .loadUser(userRequest);

        verifyNoInteractions(provisioningService);
    }

    private void stubKakaoRequest() {
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("kakao");
    }

    private void stubValidKakaoUser() {
        when(delegate.loadUser(userRequest))
            .thenReturn(kakaoUser);

        when(kakaoUser.getAttributes())
            .thenReturn(
                Map.of(
                    "id",
                    123456789L,
                    "properties",
                    Map.of(
                        "nickname",
                        "Kakao 사용자",
                        "profile_image",
                        "https://example.com/kakao-profile.png"
                    )
                )
            );
    }

    private void stubUserPrincipalData() {
        when(user.getId())
            .thenReturn(USER_ID);

        when(user.getEmail())
            .thenReturn("kakao-user@oauth.invalid");

        when(user.getRole())
            .thenReturn(UserRole.USER);

        when(user.isLocked())
            .thenReturn(false);
    }
}
