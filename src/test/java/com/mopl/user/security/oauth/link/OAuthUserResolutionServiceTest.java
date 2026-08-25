package com.mopl.user.security.oauth.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.service.OAuthAccountManagementService;
import com.mopl.user.service.OAuthUserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class OAuthUserResolutionServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final String PROVIDER_USER_ID =
        "google-sub-123";

    @Mock
    OAuthUserProvisioningService provisioningService;

    @Mock
    OAuthAccountManagementService accountManagementService;

    @Mock
    OAuthLinkIntentSessionStore linkIntentSessionStore;

    @Mock
    ObjectProvider<HttpServletRequest> requestProvider;

    @Mock
    HttpServletRequest request;

    @Mock
    User user;

    @InjectMocks
    OAuthUserResolutionService resolutionService;

    @Test
    @DisplayName("HTTP 요청 문맥이 없으면 일반 OAuth 로그인으로 처리한다")
    void resolve_usesProvisioning_whenRequestIsUnavailable() {
        // given
        when(requestProvider.getIfAvailable())
            .thenReturn(null);

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID,
                "user@example.com",
                "테스트 사용자",
                "https://example.com/profile.png"
            )
        ).thenReturn(user);

        // when
        User result =
            resolutionService.resolve(
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID,
                "user@example.com",
                "테스트 사용자",
                "https://example.com/profile.png"
            );

        // then
        assertThat(result)
            .isSameAs(user);

        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID,
                "user@example.com",
                "테스트 사용자",
                "https://example.com/profile.png"
            );

        verifyNoInteractions(
            accountManagementService,
            linkIntentSessionStore
        );
    }

    @Test
    @DisplayName("연결 의도가 없으면 일반 OAuth 로그인으로 처리한다")
    void resolve_usesProvisioning_whenLinkIntentIsMissing() {
        // given
        when(requestProvider.getIfAvailable())
            .thenReturn(request);

        when(
            linkIntentSessionStore
                .hasPendingIntent(request)
        ).thenReturn(false);

        when(
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "카카오 사용자",
                null
            )
        ).thenReturn(user);

        // when
        User result =
            resolutionService.resolve(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "카카오 사용자",
                null
            );

        // then
        assertThat(result)
            .isSameAs(user);

        verify(provisioningService)
            .resolveOrCreate(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "카카오 사용자",
                null
            );

        verifyNoInteractions(
            accountManagementService
        );
    }

    @Test
    @DisplayName("유효한 연결 의도가 있으면 인증된 OAuth 계정을 기존 사용자에게 연결한다")
    void resolve_linksVerifiedAccount_whenIntentIsValid() {
        // given
        OAuthLinkIntent linkIntent =
            createLinkIntent(
                OAuthProvider.GOOGLE
            );

        when(requestProvider.getIfAvailable())
            .thenReturn(request);

        when(
            linkIntentSessionStore
                .hasPendingIntent(request)
        ).thenReturn(true);

        when(
            linkIntentSessionStore.consume(
                request,
                OAuthProvider.GOOGLE
            )
        ).thenReturn(
            Optional.of(linkIntent)
        );

        when(
            accountManagementService
                .linkVerifiedAccount(
                    linkIntent,
                    OAuthProvider.GOOGLE,
                    PROVIDER_USER_ID
                )
        ).thenReturn(user);

        // when
        User result =
            resolutionService.resolve(
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID,
                "user@example.com",
                "Google 사용자",
                null
            );

        // then
        assertThat(result)
            .isSameAs(user);

        verify(accountManagementService)
            .linkVerifiedAccount(
                linkIntent,
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID
            );

        verifyNoInteractions(
            provisioningService
        );
    }

    @Test
    @DisplayName("연결 의도가 있었지만 소비할 수 없으면 일반 로그인으로 전환하지 않는다")
    void resolve_fails_whenPendingIntentIsInvalid() {
        // given
        when(requestProvider.getIfAvailable())
            .thenReturn(request);

        when(
            linkIntentSessionStore
                .hasPendingIntent(request)
        ).thenReturn(true);

        when(
            linkIntentSessionStore.consume(
                request,
                OAuthProvider.NAVER
            )
        ).thenReturn(
            Optional.empty()
        );

        // when & then
        assertThatThrownBy(() ->
            resolutionService.resolve(
                OAuthProvider.NAVER,
                "naver-user-id",
                null,
                "Naver 사용자",
                null
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException
                    oauthException =
                    (OAuth2AuthenticationException)
                        exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    OAuthUserResolutionService
                        .INVALID_LINK_INTENT
                );
            });

        verifyNoInteractions(
            provisioningService,
            accountManagementService
        );
    }

    @Test
    @DisplayName("이미 다른 사용자에게 연결된 OAuth 계정은 연결 충돌 인증 실패로 변환한다")
    void resolve_convertsAccountConflict() {
        // given
        OAuthLinkIntent linkIntent =
            createLinkIntent(
                OAuthProvider.GOOGLE
            );

        when(requestProvider.getIfAvailable())
            .thenReturn(request);

        when(
            linkIntentSessionStore
                .hasPendingIntent(request)
        ).thenReturn(true);

        when(
            linkIntentSessionStore.consume(
                request,
                OAuthProvider.GOOGLE
            )
        ).thenReturn(
            Optional.of(linkIntent)
        );

        when(
            accountManagementService
                .linkVerifiedAccount(
                    linkIntent,
                    OAuthProvider.GOOGLE,
                    PROVIDER_USER_ID
                )
        ).thenThrow(
            new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            )
        );

        // when & then
        assertThatThrownBy(() ->
            resolutionService.resolve(
                OAuthProvider.GOOGLE,
                PROVIDER_USER_ID,
                "user@example.com",
                "Google 사용자",
                null
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException
                    oauthException =
                    (OAuth2AuthenticationException)
                        exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    OAuthUserResolutionService
                        .ACCOUNT_LINK_CONFLICT
                );

                assertThat(
                    oauthException.getCause()
                ).isInstanceOf(
                    BusinessException.class
                );
            });

        verifyNoInteractions(
            provisioningService
        );
    }

    @Test
    @DisplayName("연결 대상 사용자가 없으면 사용자 없음 인증 실패로 변환한다")
    void resolve_convertsMissingLinkTarget() {
        // given
        OAuthLinkIntent linkIntent =
            createLinkIntent(
                OAuthProvider.KAKAO
            );

        when(requestProvider.getIfAvailable())
            .thenReturn(request);

        when(
            linkIntentSessionStore
                .hasPendingIntent(request)
        ).thenReturn(true);

        when(
            linkIntentSessionStore.consume(
                request,
                OAuthProvider.KAKAO
            )
        ).thenReturn(
            Optional.of(linkIntent)
        );

        when(
            accountManagementService
                .linkVerifiedAccount(
                    linkIntent,
                    OAuthProvider.KAKAO,
                    "123456789"
                )
        ).thenThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        );

        // when & then
        assertThatThrownBy(() ->
            resolutionService.resolve(
                OAuthProvider.KAKAO,
                "123456789",
                null,
                "Kakao 사용자",
                null
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException
                    oauthException =
                    (OAuth2AuthenticationException)
                        exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    OAuthUserResolutionService
                        .LINK_TARGET_NOT_FOUND
                );
            });

        verifyNoInteractions(
            provisioningService
        );
    }

    private OAuthLinkIntent createLinkIntent(
        OAuthProvider provider
    ) {
        return new OAuthLinkIntent(
            USER_ID,
            provider,
            Instant.parse(
                "2026-08-24T12:00:00Z"
            )
        );
    }
}
