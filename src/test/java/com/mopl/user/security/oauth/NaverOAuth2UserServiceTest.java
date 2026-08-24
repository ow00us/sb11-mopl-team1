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
import com.mopl.user.security.oauth.link.OAuthUserResolutionService;
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
 * Naver OAuth2 사용자 정보를 MOPL 공통 Principal로 변환하는
 * 정상 흐름과 보안 실패 흐름을 검증
 */
@ExtendWith(MockitoExtension.class)
class NaverOAuth2UserServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    @Mock
    OAuthUserResolutionService userResolutionService;

    @Mock
    OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    @Mock
    OAuth2UserRequest userRequest;

    @Mock
    ClientRegistration clientRegistration;

    @Mock
    OAuth2User naverUser;

    @Mock
    User user;

    NaverOAuth2UserService naverOAuth2UserService;

    @BeforeEach
    void setUp() {
        naverOAuth2UserService =
            new NaverOAuth2UserService(
                userResolutionService,
                delegate
            );
    }

    @Test
    @DisplayName("Naver 사용자는 MOPL OAuth2 Principal로 변환된다")
    void loadUser_success() {
        // given
        stubNaverRequest();

        Map<String, Object> attributes =
            Map.of(
                "resultcode",
                "00",
                "message",
                "success",
                "response",
                Map.of(
                    "id",
                    " naver-user-id-123 ",
                    "nickname",
                    " Naver 사용자 ",
                    "profile_image",
                    " https://example.com/naver-profile.png "
                )
            );

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(attributes);

        when(
            userResolutionService.resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 사용자",
                "https://example.com/naver-profile.png"
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        OAuth2User result =
            naverOAuth2UserService.loadUser(
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
            .isEqualTo("naver-user@oauth.invalid");
        assertThat(moplUser.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(moplUser.getProvider())
            .isEqualTo(OAuthProvider.NAVER);
        assertThat(moplUser.getProviderUserId())
            .isEqualTo("naver-user-id-123");
        assertThat(moplUser.getName())
            .isEqualTo("NAVER:naver-user-id-123");
        assertThat(moplUser.getAttributes())
            .isEqualTo(attributes);

        verify(delegate)
            .loadUser(userRequest);

        verify(userResolutionService)
            .resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 사용자",
                "https://example.com/naver-profile.png"
            );
    }

    @Test
    @DisplayName("닉네임이 없으면 이름을 사용자 이름으로 사용한다")
    void loadUser_usesNameWhenNicknameIsMissing() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "00",
                    "response",
                    Map.of(
                        "id",
                        "naver-user-id-123",
                        "name",
                        "Naver 실명"
                    )
                )
            );

        when(
            userResolutionService.resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 실명",
                null
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        naverOAuth2UserService.loadUser(
            userRequest
        );

        // then
        verify(userResolutionService)
            .resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 실명",
                null
            );
    }

    @Test
    @DisplayName("선택 프로필 정보가 없으면 기본 이름과 null 이미지를 사용한다")
    void loadUser_usesDefaultsForOptionalProfile() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "00",
                    "response",
                    Map.of(
                        "id",
                        "naver-user-id-123"
                    )
                )
            );

        when(
            userResolutionService.resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 사용자",
                null
            )
        ).thenReturn(user);

        stubUserPrincipalData();

        // when
        naverOAuth2UserService.loadUser(
            userRequest
        );

        // then
        verify(userResolutionService)
            .resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 사용자",
                null
            );
    }

    @Test
    @DisplayName("Naver UserInfo 결과 코드가 성공이 아니면 사용자 생성을 거부한다")
    void loadUser_fail_whenResultCodeIsNotSuccess() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        /*
         * response에 형식상 유효한 ID가 있어도 resultcode가 실패이면
         * 신뢰하지 않고 사용자 생성 전에 인증을 중단
         */
        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "024",
                    "message",
                    "Authentication failed",
                    "response",
                    Map.of(
                        "id",
                        "naver-user-id-123"
                    )
                )
            );

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
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
                    NaverOAuth2UserService
                        .INVALID_NAVER_USER_INFO
                );
            });

        verifyNoInteractions(userResolutionService);
    }

    @Test
    @DisplayName("response 객체가 없으면 사용자 조회 전에 인증에 실패한다")
    void loadUser_fail_whenResponseIsMissing() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "00",
                    "message",
                    "success"
                )
            );

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
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
                    NaverOAuth2UserService
                        .INVALID_NAVER_USER_INFO
                );
            });

        verifyNoInteractions(userResolutionService);
    }

    @Test
    @DisplayName("Naver 사용자 ID가 없으면 사용자 조회 전에 인증에 실패한다")
    void loadUser_fail_whenUserIdIsMissing() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "00",
                    "response",
                    Map.of(
                        "nickname",
                        "Naver 사용자"
                    )
                )
            );

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
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
                    NaverOAuth2UserService
                        .INVALID_NAVER_USER_ID
                );
            });

        verifyNoInteractions(userResolutionService);
    }

    @Test
    @DisplayName("Naver attributes가 없으면 인증에 실패한다")
    void loadUser_fail_whenAttributesAreMissing() {
        // given
        stubNaverRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(null);

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
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
                    "naver_user_info_missing"
                );
            });

        verifyNoInteractions(userResolutionService);
    }

    @Test
    @DisplayName("잠긴 MOPL 사용자는 Naver 인증에도 성공할 수 없다")
    void loadUser_fail_whenAccountIsLocked() {
        // given
        stubNaverRequest();
        stubValidNaverUser();

        when(
            userResolutionService.resolve(
                OAuthProvider.NAVER,
                "naver-user-id-123",
                null,
                "Naver 사용자",
                "https://example.com/naver-profile.png"
            )
        ).thenReturn(user);

        when(user.isLocked())
            .thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
                userRequest
            )
        )
            .isInstanceOf(LockedException.class)
            .hasMessage(
                NaverOAuth2UserService
                    .NAVER_ACCOUNT_LOCKED
            );
    }

    @Test
    @DisplayName("Naver가 아닌 OAuth2 요청은 delegate 호출 전에 거부한다")
    void loadUser_fail_whenProviderIsNotNaver() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("kakao");

        // when & then
        assertThatThrownBy(() ->
            naverOAuth2UserService.loadUser(
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

        verifyNoInteractions(userResolutionService);
    }

    private void stubNaverRequest() {
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("naver");
    }

    private void stubValidNaverUser() {
        when(delegate.loadUser(userRequest))
            .thenReturn(naverUser);

        when(naverUser.getAttributes())
            .thenReturn(
                Map.of(
                    "resultcode",
                    "00",
                    "response",
                    Map.of(
                        "id",
                        "naver-user-id-123",
                        "nickname",
                        "Naver 사용자",
                        "profile_image",
                        "https://example.com/naver-profile.png"
                    )
                )
            );
    }

    private void stubUserPrincipalData() {
        when(user.getId())
            .thenReturn(USER_ID);

        when(user.getEmail())
            .thenReturn("naver-user@oauth.invalid");

        when(user.getRole())
            .thenReturn(UserRole.USER);

        when(user.isLocked())
            .thenReturn(false);
    }
}
