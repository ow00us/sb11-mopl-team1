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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Google OIDC 사용자 정보를 MOPL 사용자 Principal로 변환하는
 * 정상 흐름과 보안 실패 흐름을 검증
 */
@ExtendWith(MockitoExtension.class)
class GoogleOidcUserServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Mock
    OAuthUserProvisioningService provisioningService;

    @Mock
    OAuth2UserService<OidcUserRequest, OidcUser> delegate;

    @Mock
    OidcUserRequest userRequest;

    @Mock
    ClientRegistration clientRegistration;

    @Mock
    OidcUser googleUser;

    @Mock
    User user;

    GoogleOidcUserService googleOidcUserService;

    OidcIdToken idToken;

    OidcUserInfo userInfo;

    @BeforeEach
    void setUp() {
        googleOidcUserService =
            new GoogleOidcUserService(
                provisioningService,
                delegate
            );

        Instant issuedAt =
            Instant.parse("2026-08-21T00:00:00Z");

        Map<String, Object> claims = Map.of(
            "sub",
            "google-sub-123",
            "email",
            "User@Example.Com",
            "email_verified",
            true,
            "name",
            "Google 사용자",
            "picture",
            "https://example.com/profile.png"
        );

        idToken =
            new OidcIdToken(
                "masked-test-id-token",
                issuedAt,
                issuedAt.plusSeconds(300),
                claims
            );

        userInfo =
            new OidcUserInfo(claims);
    }

    @Test
    @DisplayName("검증된 Google 사용자는 MOPL OIDC Principal로 변환된다")
    void loadUser_success() {
        // given
        stubGoogleRequest();
        stubValidGoogleUser();
        stubGooglePrincipalData();

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "User@Example.Com",
                "Google 사용자",
                "https://example.com/profile.png"
            )
        ).thenReturn(user);

        when(user.getId())
            .thenReturn(USER_ID);
        when(user.getEmail())
            .thenReturn("user@example.com");
        when(user.getRole())
            .thenReturn(UserRole.USER);
        when(user.isLocked())
            .thenReturn(false);

        // when
        OidcUser result =
            googleOidcUserService.loadUser(
                userRequest
            );

        // then
        assertThat(result)
            .isInstanceOf(MoplOidcUser.class);

        MoplOidcUser moplUser =
            (MoplOidcUser) result;

        assertThat(moplUser.getUserId())
            .isEqualTo(USER_ID);
        assertThat(moplUser.getEmail())
            .isEqualTo("user@example.com");
        assertThat(moplUser.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(moplUser.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(moplUser.getProviderUserId())
            .isEqualTo("google-sub-123");
        assertThat(moplUser.getName())
            .isEqualTo("GOOGLE:google-sub-123");
        assertThat(moplUser.getIdToken())
            .isSameAs(idToken);
        assertThat(moplUser.getUserInfo())
            .isSameAs(userInfo);

        verify(delegate)
            .loadUser(userRequest);
    }

    @Test
    @DisplayName("Google 이름과 프로필 이미지가 없으면 기본 이름과 null을 사용한다")
    void loadUser_usesDefaultsForOptionalProfile() {
        // given
        stubGoogleRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(googleUser);

        when(googleUser.getSubject())
            .thenReturn("google-sub-123");
        when(googleUser.getEmail())
            .thenReturn("user@example.com");
        when(googleUser.getEmailVerified())
            .thenReturn(true);
        when(googleUser.getFullName())
            .thenReturn(" ");
        when(googleUser.getPicture())
            .thenReturn(null);

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            )
        ).thenReturn(user);

        when(user.getId())
            .thenReturn(USER_ID);
        when(user.getEmail())
            .thenReturn("user@example.com");
        when(user.getRole())
            .thenReturn(UserRole.USER);
        when(user.isLocked())
            .thenReturn(false);

        when(googleUser.getAttributes())
            .thenReturn(
                Map.of(
                    "sub",
                    "google-sub-123"
                )
            );
        when(googleUser.getIdToken())
            .thenReturn(idToken);
        when(googleUser.getUserInfo())
            .thenReturn(userInfo);

        // when
        googleOidcUserService.loadUser(
            userRequest
        );

        // then
        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            );
    }

    @Test
    @DisplayName("Google 이메일이 검증되지 않았으면 사용자 생성 전에 인증에 실패한다")
    void loadUser_fail_whenEmailIsNotVerified() {
        // given
        stubGoogleRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(googleUser);
        when(googleUser.getSubject())
            .thenReturn("google-sub-123");
        when(googleUser.getEmail())
            .thenReturn("user@example.com");
        when(googleUser.getEmailVerified())
            .thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            googleOidcUserService.loadUser(
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
                    GoogleOidcUserService
                        .GOOGLE_EMAIL_NOT_VERIFIED
                );
            });

        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("Google subject가 없으면 사용자 조회 전에 인증에 실패한다")
    void loadUser_fail_whenSubjectIsMissing() {
        // given
        stubGoogleRequest();

        when(delegate.loadUser(userRequest))
            .thenReturn(googleUser);
        when(googleUser.getSubject())
            .thenReturn(" ");

        // when & then
        assertThatThrownBy(() ->
            googleOidcUserService.loadUser(
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
                    GoogleOidcUserService
                        .INVALID_GOOGLE_SUBJECT
                );
            });

        verifyNoInteractions(provisioningService);
    }

    @Test
    @DisplayName("잠긴 MOPL 사용자는 Google 인증에도 성공할 수 없다")
    void loadUser_fail_whenAccountIsLocked() {
        // given
        stubGoogleRequest();
        stubValidGoogleUser();

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "User@Example.Com",
                "Google 사용자",
                "https://example.com/profile.png"
            )
        ).thenReturn(user);

        when(user.isLocked())
            .thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
            googleOidcUserService.loadUser(
                userRequest
            )
        )
            .isInstanceOf(LockedException.class)
            .hasMessage(
                GoogleOidcUserService
                    .GOOGLE_ACCOUNT_LOCKED
            );
    }

    @Test
    @DisplayName("Google이 아닌 OIDC 요청은 delegate 호출 전에 거부한다")
    void loadUser_fail_whenProviderIsNotGoogle() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("kakao");

        // when & then
        assertThatThrownBy(() ->
            googleOidcUserService.loadUser(
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
                    "unsupported_oidc_provider"
                );
            });

        verify(delegate, never())
            .loadUser(userRequest);
        verifyNoInteractions(provisioningService);
    }

    private void stubGoogleRequest() {
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("google");
    }

    private void stubValidGoogleUser() {
        when(delegate.loadUser(userRequest))
            .thenReturn(googleUser);

        when(googleUser.getSubject())
            .thenReturn("google-sub-123");
        when(googleUser.getEmail())
            .thenReturn("User@Example.Com");
        when(googleUser.getEmailVerified())
            .thenReturn(true);
        when(googleUser.getFullName())
            .thenReturn(" Google 사용자 ");
        when(googleUser.getPicture())
            .thenReturn(
                " https://example.com/profile.png "
            );
    }

    private void stubGooglePrincipalData() {
        when(googleUser.getAttributes())
            .thenReturn(
                Map.of(
                    "sub",
                    "google-sub-123",
                    "email",
                    "User@Example.Com"
                )
            );

        when(googleUser.getIdToken())
            .thenReturn(idToken);

        when(googleUser.getUserInfo())
            .thenReturn(userInfo);
    }
}
