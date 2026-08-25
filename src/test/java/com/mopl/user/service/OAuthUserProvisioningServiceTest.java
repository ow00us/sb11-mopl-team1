package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * OAuth 계정에 연결된 사용자를 조회하거나 신규 소셜 사용자를 생성하는
 * 서비스의 분기와 보안 정책을 검증
 */
@ExtendWith(MockitoExtension.class)
class OAuthUserProvisioningServiceTest {

    @Mock
    OAuthAccountRepository oauthAccountRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    OAuthUserCreationService oauthUserCreationService;

    OAuthUserProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        provisioningService =
            new OAuthUserProvisioningService(
                oauthAccountRepository,
                userRepository,
                oauthUserCreationService
            );
    }

    @Test
    @DisplayName("이미 연결된 OAuth 계정은 기존 사용자를 반환한다")
    void resolveOrCreate_returnsLinkedUser() {
        // given
        User linkedUser =
            User.builder()
                .email("stored@example.com")
                .passwordHash(null)
                .name("기존 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        OAuthAccount linkedAccount =
            OAuthAccount.builder()
                .user(linkedUser)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        ).thenReturn(
            Optional.of(linkedAccount)
        );

        // when
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                null,
                null,
                null
            );

        // then
        assertThat(result)
            .isSameAs(linkedUser);

        /*
         * 이미 연결된 계정은 Google 이메일이 변경되거나 누락되어도
         * 신규 사용자를 만들지 않는다.
         */
        verify(userRepository, never())
            .existsByEmail(any());
        verify(oauthUserCreationService, never())
            .create(
                any(),
                any(),
                any(),
                any(),
                any()
            );
    }

    @Test
    @DisplayName("연결되지 않은 OAuth 사용자는 검증된 정보로 생성 서비스를 호출한다")
    void resolveOrCreate_createsOAuthOnlyUser() {
        // given
        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        ).thenReturn(
            Optional.empty()
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(false);

        User createdUser =
            User.builder()
                .email("user@example.com")
                .passwordHash(null)
                .name("Google 사용자")
                .profileImageUrl(
                    "https://example.com/profile.png"
                )
                .role(UserRole.USER)
                .locked(false)
                .build();

        when(
            oauthUserCreationService.create(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                "https://example.com/profile.png"
            )
        ).thenReturn(createdUser);

        // when
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                " User@Example.Com ",
                " Google 사용자 ",
                " https://example.com/profile.png "
            );

        // then
        assertThat(result)
            .isSameAs(createdUser);

        verify(oauthUserCreationService)
            .create(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                "https://example.com/profile.png"
            );
    }

    @Test
    @DisplayName("동일 이메일의 기존 사용자는 자동으로 OAuth 계정과 연결하지 않는다")
    void resolveOrCreate_rejectsExistingEmail() {
        // given
        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        ).thenReturn(
            Optional.empty()
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
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
                    OAuthUserProvisioningService
                        .ACCOUNT_LINK_REQUIRED
                );
            });

        verify(oauthUserCreationService, never())
            .create(
                any(),
                any(),
                any(),
                any(),
                any()
            );
    }

    @Test
    @DisplayName("동시에 생성된 같은 OAuth 계정은 먼저 생성된 사용자로 수렴한다")
    void resolveOrCreate_returnsWinnerAfterConcurrentConflict() {
        // given
        User winner =
            User.builder()
                .email("user@example.com")
                .passwordHash(null)
                .name("Google 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        OAuthAccount winnerAccount =
            OAuthAccount.builder()
                .user(winner)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        )
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winnerAccount));

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(false);

        when(
            oauthUserCreationService.create(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            )
        ).thenThrow(
            new DataIntegrityViolationException(
                "concurrent OAuth account"
            )
        );

        // when
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            );

        // then
        assertThat(result)
            .isSameAs(winner);

        verify(
            oauthAccountRepository,
            times(2)
        ).findByProviderAndProviderUserId(
            OAuthProvider.GOOGLE,
            "google-sub-123"
        );
    }

    @Test
    @DisplayName("생성 충돌 후 동일 이메일만 존재하면 자동 연결하지 않는다")
    void resolveOrCreate_rejectsEmailConflictAfterCreationRace() {
        // given
        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        )
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());

        /*
         * 최초 검사 시에는 없었지만 생성 경쟁이 발생한 뒤
         * 같은 이메일 사용자가 저장된 상황을 재현한다.
         */
        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        )
            .thenReturn(false)
            .thenReturn(true);

        DataIntegrityViolationException conflict =
            new DataIntegrityViolationException(
                "duplicated email"
            );

        when(
            oauthUserCreationService.create(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            )
        ).thenThrow(conflict);

        // when & then
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
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
                    OAuthUserProvisioningService
                        .ACCOUNT_LINK_REQUIRED
                );

                assertThat(oauthException.getCause())
                    .isSameAs(conflict);
            });
    }

    @Test
    @DisplayName("생성 충돌 후 사용자와 연결 정보가 모두 없으면 인증에 실패한다")
    void resolveOrCreate_rejectsUnresolvedCreationConflict() {
        // given
        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
        )
            .thenReturn(Optional.empty())
            .thenReturn(Optional.empty());

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        )
            .thenReturn(false)
            .thenReturn(false);

        DataIntegrityViolationException conflict =
            new DataIntegrityViolationException(
                "unknown persistence conflict"
            );

        when(
            oauthUserCreationService.create(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            )
        ).thenThrow(conflict);

        // when & then
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
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
                    OAuthUserProvisioningService
                        .ACCOUNT_CREATION_CONFLICT
                );

                assertThat(oauthException.getCause())
                    .isSameAs(conflict);
            });
    }

    @Test
    @DisplayName("이메일이 없는 OAuth 사용자는 내부 식별 이메일로 생성한다")
    void resolveOrCreate_createsInternalEmailWhenEmailIsMissing() {
        // given
        when(
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.KAKAO,
                    "kakao-user-123"
                )
        ).thenReturn(
            Optional.empty()
        );

        when(
            userRepository.existsByEmail(
                any()
            )
        ).thenReturn(false);

        User createdUser =
            User.builder()
                .email("generated@oauth.invalid")
                .passwordHash(null)
                .name("Kakao 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        when(
            oauthUserCreationService.create(
                eq(OAuthProvider.KAKAO),
                eq("kakao-user-123"),
                any(),
                eq("Kakao 사용자"),
                isNull()
            )
        ).thenReturn(createdUser);

        // when
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.KAKAO,
                "kakao-user-123",
                null,
                "Kakao 사용자",
                null
            );

        // then
        assertThat(result)
            .isSameAs(createdUser);

        ArgumentCaptor<String> emailCaptor =
            ArgumentCaptor.forClass(String.class);

        verify(oauthUserCreationService)
            .create(
                eq(OAuthProvider.KAKAO),
                eq("kakao-user-123"),
                emailCaptor.capture(),
                eq("Kakao 사용자"),
                isNull()
            );

        String generatedEmail =
            emailCaptor.getValue();

        assertThat(generatedEmail)
            .startsWith("kakao-")
            .endsWith("@oauth.invalid");

        assertThat(generatedEmail)
            .matches(
                "kakao-[0-9a-f]{8}-"
                    + "[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-"
                    + "[0-9a-f]{12}"
                    + "@oauth\\.invalid"
            );

        verify(userRepository)
            .existsByEmail(generatedEmail);
    }

    @Test
    @DisplayName("Provider 사용자 ID가 없으면 사용자 조회와 생성을 수행하지 않는다")
    void resolveOrCreate_rejectsMissingProviderUserId() {
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                " ",
                "user@example.com",
                "Google 사용자",
                null
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
                    "invalid_provider_user_id"
                );
            });

        verify(oauthAccountRepository, never())
            .findByProviderAndProviderUserId(
                any(),
                any()
            );
        verify(oauthUserCreationService, never())
            .create(
                any(),
                any(),
                any(),
                any(),
                any()
            );
    }
}
