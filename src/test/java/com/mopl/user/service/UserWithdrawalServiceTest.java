package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProperties;
import com.mopl.user.storage.AccessTokenBlockStore;
import com.mopl.user.storage.EmailVerificationStore;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {
    private static final Duration ACCESS_TOKEN_EXPIRATION =
        Duration.ofHours(3);

    @Mock
    UserWithdrawalTransactionService transactionService;

    @Mock
    RefreshTokenStore refreshTokenStore;

    @Mock
    EmailVerificationStore emailVerificationStore;

    @Mock
    AccessTokenBlockStore accessTokenBlockStore;

    @Mock
    JwtProperties jwtProperties;

    @InjectMocks
    UserWithdrawalService userWithdrawalService;

    @Test
    @DisplayName("DB 탈퇴 처리 후 사용자의 모든 인증 상태를 정리한다")
    void withdraw_cleansAuthenticationStateAfterDatabaseCommit() {
        // given
        UUID userId = UUID.randomUUID();

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(
            ACCESS_TOKEN_EXPIRATION
        );

        // when
        userWithdrawalService.withdraw(
            userId,
            userId
        );

        // then
        InOrder inOrder = inOrder(
            accessTokenBlockStore,
            transactionService,
            refreshTokenStore,
            emailVerificationStore
        );

        inOrder.verify(accessTokenBlockStore)
            .block(
                userId,
                ACCESS_TOKEN_EXPIRATION
            );

        inOrder.verify(transactionService).withdraw(
            userId,
            userId
        );

        inOrder.verify(refreshTokenStore)
            .revokeAllByUserId(userId);

        inOrder.verify(emailVerificationStore)
            .deleteByUserId(userId);
    }

    @Test
    @DisplayName("DB 탈퇴 처리에 실패해도 선행 Access Token 차단 상태를 유지한다")
    void withdraw_keepsAccessTokenBlockWhenDatabaseWithdrawalFails() {
        // given
        UUID userId = UUID.randomUUID();
        IllegalStateException exception =
            new IllegalStateException(
                "database withdrawal failed"
            );

        doThrow(exception)
            .when(transactionService)
            .withdraw(userId, userId);

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(
            ACCESS_TOKEN_EXPIRATION
        );

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalService.withdraw(
                userId,
                userId
            )
        ).isSameAs(exception);

        verifyNoInteractions(
            refreshTokenStore,
            emailVerificationStore
        );

        verify(accessTokenBlockStore)
            .block(
                userId,
                ACCESS_TOKEN_EXPIRATION
            );
    }

    @Test
    @DisplayName("Refresh Token 폐기에 실패해도 이메일 인증 상태를 정리한다")
    void withdraw_continuesCleanupWhenRefreshTokenRevocationFails() {
        // given
        UUID userId = UUID.randomUUID();

        when(
            refreshTokenStore.revokeAllByUserId(
                userId
            )
        ).thenThrow(
            new IllegalStateException(
                "redis unavailable"
            )
        );

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(
            ACCESS_TOKEN_EXPIRATION
        );

        // when & then
        assertThatCode(() ->
            userWithdrawalService.withdraw(
                userId,
                userId
            )
        ).doesNotThrowAnyException();

        verify(emailVerificationStore)
            .deleteByUserId(userId);
    }

    @Test
    @DisplayName("이메일 인증 상태 삭제에 실패해도 완료된 탈퇴는 성공 처리한다")
    void withdraw_ignoresEmailVerificationCleanupFailure() {
        // given
        UUID userId = UUID.randomUUID();

        doThrow(
            new IllegalStateException(
                "redis unavailable"
            )
        )
            .when(emailVerificationStore)
            .deleteByUserId(userId);

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(
            ACCESS_TOKEN_EXPIRATION
        );

        // when & then
        assertThatCode(() ->
            userWithdrawalService.withdraw(
                userId,
                userId
            )
        ).doesNotThrowAnyException();

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("Access Token 차단 상태를 저장하지 못하면 DB 탈퇴를 시작하지 않는다")
    void withdraw_rejectsWhenAccessTokenBlockFails() {
        // given
        UUID userId =
            UUID.randomUUID();

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(
            ACCESS_TOKEN_EXPIRATION
        );

        doThrow(
            new QueryTimeoutException(
                "Redis unavailable"
            )
        )
            .when(accessTokenBlockStore)
            .block(
                userId,
                ACCESS_TOKEN_EXPIRATION
            );

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalService.withdraw(
                userId,
                userId
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.SERVICE_UNAVAILABLE
                    )
            );

        verifyNoInteractions(
            transactionService,
            refreshTokenStore,
            emailVerificationStore
        );
    }

    @Test
    @DisplayName("본인이 아닌 사용자 탈퇴 요청은 Access Token 차단 전에 거부한다")
    void withdraw_rejectsDifferentUserBeforeBlocking() {
        // given
        UUID authenticatedUserId =
            UUID.randomUUID();

        UUID targetUserId =
            UUID.randomUUID();

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalService.withdraw(
                authenticatedUserId,
                targetUserId
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.FORBIDDEN
                    )
            );

        verifyNoInteractions(
            accessTokenBlockStore,
            transactionService,
            refreshTokenStore,
            emailVerificationStore,
            jwtProperties
        );
    }
}
