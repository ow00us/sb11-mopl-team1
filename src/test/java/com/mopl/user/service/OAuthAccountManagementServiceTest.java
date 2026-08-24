package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.OAuthAccountDto;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OAuthAccountManagementServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    OAuthAccountRepository oauthAccountRepository;

    @Mock
    RefreshTokenStore refreshTokenStore;

    @InjectMocks
    OAuthAccountManagementService oauthAccountManagementService;

    @Test
    @DisplayName("본인에게 연결된 OAuth 계정을 연결 시각 순서로 조회한다")
    void getLinkedAccounts_success() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User user = createUser();

        OAuthAccount googleAccount =
            createOAuthAccount(
                user,
                OAuthProvider.GOOGLE,
                "google-subject",
                Instant.parse("2026-08-01T01:00:00Z")
            );

        OAuthAccount naverAccount =
            createOAuthAccount(
                user,
                OAuthProvider.NAVER,
                "naver-id",
                Instant.parse("2026-08-02T01:00:00Z")
            );

        when(userRepository.existsById(userId))
            .thenReturn(true);

        when(
            oauthAccountRepository
                .findAllByUserIdOrderByCreatedAtAsc(
                    userId
                )
        ).thenReturn(
            List.of(
                googleAccount,
                naverAccount
            )
        );

        List<OAuthAccountDto> response =
            oauthAccountManagementService
                .getLinkedAccounts(
                    userId,
                    userId
                );

        assertThat(response)
            .extracting(OAuthAccountDto::provider)
            .containsExactly(
                OAuthProvider.GOOGLE,
                OAuthProvider.NAVER
            );

        assertThat(response)
            .extracting(OAuthAccountDto::connectedAt)
            .containsExactly(
                Instant.parse("2026-08-01T01:00:00Z"),
                Instant.parse("2026-08-02T01:00:00Z")
            );

        verify(userRepository)
            .existsById(userId);

        verify(oauthAccountRepository)
            .findAllByUserIdOrderByCreatedAtAsc(
                userId
            );
    }

    @Test
    @DisplayName("연결된 OAuth 계정이 없으면 빈 목록을 반환한다")
    void getLinkedAccounts_success_whenNoAccountExists() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        when(userRepository.existsById(userId))
            .thenReturn(true);

        when(
            oauthAccountRepository
                .findAllByUserIdOrderByCreatedAtAsc(
                    userId
                )
        ).thenReturn(List.of());

        List<OAuthAccountDto> response =
            oauthAccountManagementService
                .getLinkedAccounts(
                    userId,
                    userId
                );

        assertThat(response).isEmpty();

        verify(userRepository)
            .existsById(userId);

        verify(oauthAccountRepository)
            .findAllByUserIdOrderByCreatedAtAsc(
                userId
            );
    }

    @Test
    @DisplayName("인증 사용자 정보가 없으면 OAuth 계정 목록 조회를 거부한다")
    void getLinkedAccounts_fail_whenUnauthenticated() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        assertThatThrownBy(() ->
            oauthAccountManagementService
                .getLinkedAccounts(
                    null,
                    userId
                )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(
            userRepository,
            oauthAccountRepository
        );
    }

    @Test
    @DisplayName("다른 사용자의 OAuth 계정 목록 조회를 거부한다")
    void getLinkedAccounts_fail_whenUserIsNotSelf() {
        UUID authenticatedUserId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        UUID targetUserId =
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            );

        assertThatThrownBy(() ->
            oauthAccountManagementService
                .getLinkedAccounts(
                    authenticatedUserId,
                    targetUserId
                )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(
            userRepository,
            oauthAccountRepository
        );
    }

    @Test
    @DisplayName("인증 사용자가 존재하지 않으면 404 오류를 반환한다")
    void getLinkedAccounts_fail_whenUserDoesNotExist() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        when(userRepository.existsById(userId))
            .thenReturn(false);

        assertThatThrownBy(() ->
            oauthAccountManagementService
                .getLinkedAccounts(
                    userId,
                    userId
                )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository)
            .existsById(userId);

        verifyNoInteractions(
            oauthAccountRepository
        );
    }

    @Test
    @DisplayName("로컬 로그인 사용자는 마지막 OAuth 계정을 해제할 수 있다")
    void unlinkAccount_success_whenLocalLoginExists() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User user = createUser();

        OAuthAccount oauthAccount =
            createOAuthAccount(
                user,
                OAuthProvider.GOOGLE,
                "google-subject",
                Instant.parse("2026-08-01T01:00:00Z")
            );

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(user));

        when(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.GOOGLE
                )
        ).thenReturn(Optional.of(oauthAccount));

        oauthAccountManagementService.unlinkAccount(
            userId,
            userId,
            OAuthProvider.GOOGLE
        );

        verify(userRepository)
            .findByIdForUpdate(userId);

        verify(oauthAccountRepository)
            .findByUserIdAndProvider(
                userId,
                OAuthProvider.GOOGLE
            );

        /*
         * 로컬 비밀번호 로그인이 가능하므로
         * OAuth 연결 개수를 조회할 필요가 없다.
         */
        verify(
            oauthAccountRepository,
            never()
        ).countByUserId(userId);

        verify(oauthAccountRepository)
            .delete(oauthAccount);

        verify(oauthAccountRepository)
            .flush();

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("OAuth 전용 사용자는 여러 연결 중 하나를 해제할 수 있다")
    void unlinkAccount_success_whenAnotherOAuthAccountExists() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User oauthOnlyUser =
            createUser(null);

        OAuthAccount oauthAccount =
            createOAuthAccount(
                oauthOnlyUser,
                OAuthProvider.KAKAO,
                "kakao-id",
                Instant.parse("2026-08-01T01:00:00Z")
            );

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(oauthOnlyUser));

        when(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.KAKAO
                )
        ).thenReturn(Optional.of(oauthAccount));

        when(oauthAccountRepository.countByUserId(userId))
            .thenReturn(2L);

        oauthAccountManagementService.unlinkAccount(
            userId,
            userId,
            OAuthProvider.KAKAO
        );

        verify(oauthAccountRepository)
            .countByUserId(userId);

        verify(oauthAccountRepository)
            .delete(oauthAccount);

        verify(oauthAccountRepository)
            .flush();

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("OAuth 전용 사용자의 마지막 로그인 수단은 해제할 수 없다")
    void unlinkAccount_fail_whenItIsLastLoginMethod() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User oauthOnlyUser =
            createUser(null);

        OAuthAccount oauthAccount =
            createOAuthAccount(
                oauthOnlyUser,
                OAuthProvider.NAVER,
                "naver-id",
                Instant.parse("2026-08-01T01:00:00Z")
            );

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(oauthOnlyUser));

        when(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.NAVER
                )
        ).thenReturn(Optional.of(oauthAccount));

        when(oauthAccountRepository.countByUserId(userId))
            .thenReturn(1L);

        assertThatThrownBy(() ->
            oauthAccountManagementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.NAVER
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(
                ErrorCode.OAUTH_LAST_LOGIN_METHOD
            );

        verify(
            oauthAccountRepository,
            never()
        ).delete(oauthAccount);

        verify(
            oauthAccountRepository,
            never()
        ).flush();

        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("연결되지 않은 OAuth Provider를 해제하면 404를 반환한다")
    void unlinkAccount_fail_whenAccountDoesNotExist() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User user = createUser();

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(user));

        when(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.GOOGLE
                )
        ).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            oauthAccountManagementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(
                ErrorCode.OAUTH_ACCOUNT_NOT_FOUND
            );

        verify(
            oauthAccountRepository,
            never()
        ).flush();

        verifyNoInteractions(refreshTokenStore);
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 OAuth 연결 해제를 거부한다")
    void unlinkAccount_fail_whenUserDoesNotExist() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            oauthAccountManagementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(
            oauthAccountRepository,
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("Provider가 없으면 저장소를 호출하지 않고 요청을 거부한다")
    void unlinkAccount_fail_whenProviderIsNull() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        assertThatThrownBy(() ->
            oauthAccountManagementService.unlinkAccount(
                userId,
                userId,
                null
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(
            userRepository,
            oauthAccountRepository,
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("Refresh Token 폐기에 실패하면 연결 해제 요청도 실패한다")
    void unlinkAccount_fail_whenSessionRevocationFails() {
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User user = createUser();

        OAuthAccount oauthAccount =
            createOAuthAccount(
                user,
                OAuthProvider.GOOGLE,
                "google-subject",
                Instant.parse("2026-08-01T01:00:00Z")
            );

        when(userRepository.findByIdForUpdate(userId))
            .thenReturn(Optional.of(user));

        when(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.GOOGLE
                )
        ).thenReturn(Optional.of(oauthAccount));

        when(refreshTokenStore.revokeAllByUserId(userId))
            .thenThrow(
                new IllegalStateException(
                    "Redis unavailable"
                )
            );

        assertThatThrownBy(() ->
            oauthAccountManagementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            )
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Redis unavailable");

        verify(oauthAccountRepository)
            .delete(oauthAccount);

        verify(oauthAccountRepository)
            .flush();

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    private User createUser() {
        return createUser("encoded-password");
    }

    private User createUser(
        String passwordHash
    ) {
        return User.builder()
            .email("user@example.com")
            .passwordHash(passwordHash)
            .name("테스트 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }

    private OAuthAccount createOAuthAccount(
        User user,
        OAuthProvider provider,
        String providerUserId,
        Instant createdAt
    ) {
        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(user)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();

        ReflectionTestUtils.setField(
            oauthAccount,
            "createdAt",
            createdAt
        );

        return oauthAccount;
    }
}
