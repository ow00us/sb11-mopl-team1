package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.user.storage.EmailVerificationStore;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock
    UserWithdrawalTransactionService transactionService;

    @Mock
    RefreshTokenStore refreshTokenStore;

    @Mock
    EmailVerificationStore emailVerificationStore;

    @InjectMocks
    UserWithdrawalService userWithdrawalService;

    @Test
    @DisplayName("DB 탈퇴 처리 후 사용자의 모든 인증 상태를 정리한다")
    void withdraw_cleansAuthenticationStateAfterDatabaseCommit() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        userWithdrawalService.withdraw(
            userId,
            userId
        );

        // then
        InOrder inOrder = inOrder(
            transactionService,
            refreshTokenStore,
            emailVerificationStore
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
    @DisplayName("DB 탈퇴 처리에 실패하면 Redis 인증 상태를 변경하지 않는다")
    void withdraw_doesNotCleanRedisWhenDatabaseWithdrawalFails() {
        // given
        UUID userId = UUID.randomUUID();
        IllegalStateException exception =
            new IllegalStateException(
                "database withdrawal failed"
            );

        doThrow(exception)
            .when(transactionService)
            .withdraw(userId, userId);

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
}
