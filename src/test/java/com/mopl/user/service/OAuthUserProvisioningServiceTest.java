package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    OAuthUserProvisioningService provisioningService;

    @BeforeEach
    void setUp() {
        provisioningService =
            new OAuthUserProvisioningService(
                oauthAccountRepository,
                userRepository
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
        verify(userRepository, never())
            .save(any());
        verify(oauthAccountRepository, never())
            .saveAndFlush(any());
    }

    @Test
    @DisplayName("연결되지 않은 OAuth 사용자는 소셜 전용 사용자로 생성한다")
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

        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation ->
                invocation.getArgument(0)
            );

        when(
            oauthAccountRepository.saveAndFlush(
                any(OAuthAccount.class)
            )
        ).thenAnswer(invocation ->
            invocation.getArgument(0)
        );

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
        assertThat(result.getEmail())
            .isEqualTo("user@example.com");
        assertThat(result.getPasswordHash())
            .isNull();
        assertThat(result.getName())
            .isEqualTo("Google 사용자");
        assertThat(result.getProfileImageUrl())
            .isEqualTo(
                "https://example.com/profile.png"
            );
        assertThat(result.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(result.isLocked())
            .isFalse();

        ArgumentCaptor<OAuthAccount> accountCaptor =
            ArgumentCaptor.forClass(
                OAuthAccount.class
            );

        verify(oauthAccountRepository)
            .saveAndFlush(
                accountCaptor.capture()
            );

        OAuthAccount savedAccount =
            accountCaptor.getValue();

        assertThat(savedAccount.getUser())
            .isSameAs(result);
        assertThat(savedAccount.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(savedAccount.getProviderUserId())
            .isEqualTo("google-sub-123");
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

        verify(userRepository, never())
            .save(any());
        verify(oauthAccountRepository, never())
            .saveAndFlush(any());
    }

    @Test
    @DisplayName("신규 OAuth 사용자 저장 중 유일성 충돌이 발생하면 인증에 실패한다")
    void resolveOrCreate_rejectsPersistenceConflict() {
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

        when(userRepository.save(any(User.class)))
            .thenAnswer(invocation ->
                invocation.getArgument(0)
            );

        when(
            oauthAccountRepository.saveAndFlush(
                any(OAuthAccount.class)
            )
        ).thenThrow(
            new DataIntegrityViolationException(
                "duplicated OAuth account"
            )
        );

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
                    .isInstanceOf(
                        DataIntegrityViolationException.class
                    );
            });
    }

    @Test
    @DisplayName("신규 OAuth 사용자 이메일이 없으면 생성하지 않는다")
    void resolveOrCreate_rejectsMissingEmail() {
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

        // when & then
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                null,
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
                    "oauth_email_required"
                );
            });

        verify(userRepository, never())
            .save(any());
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
        verify(userRepository, never())
            .save(any());
    }
}
