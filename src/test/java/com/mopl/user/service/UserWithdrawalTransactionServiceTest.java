package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalTransactionServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    OAuthAccountRepository oauthAccountRepository;

    @Mock
    User user;

    @InjectMocks
    UserWithdrawalTransactionService
        userWithdrawalTransactionService;

    @Test
    @DisplayName("본인 계정의 OAuth 연결을 삭제하고 사용자를 탈퇴 처리한다")
    void withdraw_success() {
        // given
        UUID userId = UUID.randomUUID();

        when(
            userRepository.findByIdForUpdate(userId)
        ).thenReturn(
            Optional.of(user)
        );

        when(
            oauthAccountRepository.deleteAllByUserId(
                userId
            )
        ).thenReturn(2L);

        // when
        userWithdrawalTransactionService.withdraw(
            userId,
            userId
        );

        // then
        verify(
            oauthAccountRepository
        ).deleteAllByUserId(
            userId
        );

        verify(user).withdraw(
            any(Instant.class)
        );
    }

    @Test
    @DisplayName("인증 사용자 UUID가 없으면 탈퇴를 거부한다")
    void withdraw_rejectsUnauthenticatedUser() {
        // given
        UUID userId = UUID.randomUUID();

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalTransactionService.withdraw(
                null,
                userId
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(
            userRepository,
            oauthAccountRepository,
            user
        );
    }

    @Test
    @DisplayName("다른 사용자의 계정은 탈퇴 처리할 수 없다")
    void withdraw_rejectsDifferentUser() {
        // given
        UUID authenticatedUserId =
            UUID.randomUUID();
        UUID targetUserId =
            UUID.randomUUID();

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalTransactionService.withdraw(
                authenticatedUserId,
                targetUserId
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(
            userRepository,
            oauthAccountRepository,
            user
        );
    }

    @Test
    @DisplayName("존재하지 않거나 이미 탈퇴한 사용자는 탈퇴 처리할 수 없다")
    void withdraw_rejectsMissingOrDeletedUser() {
        // given
        UUID userId = UUID.randomUUID();

        when(
            userRepository.findByIdForUpdate(userId)
        ).thenReturn(
            Optional.empty()
        );

        // when & then
        assertThatThrownBy(() ->
            userWithdrawalTransactionService.withdraw(
                userId,
                userId
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(
            oauthAccountRepository,
            never()
        ).deleteAllByUserId(
            userId
        );

        verify(
            user,
            never()
        ).withdraw(
            any(Instant.class)
        );
    }
}
