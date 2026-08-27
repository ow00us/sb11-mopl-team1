package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.AccessTokenBlockStore;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class AccessTokenUserStatusServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Mock
    AccessTokenBlockStore accessTokenBlockStore;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    AccessTokenUserStatusService
        accessTokenUserStatusService;

    @Test
    @DisplayName("Redis에 차단 상태가 없으면 DB 조회 없이 인증을 허용한다")
    void resolve_allowsWhenRedisDoesNotContainBlock() {
        // given
        when(
            accessTokenBlockStore.isBlocked(USER_ID)
        ).thenReturn(false);

        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                USER_ID
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.ALLOWED
            );

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Redis에 차단 상태가 있으면 인증을 거부한다")
    void resolve_blocksWhenRedisContainsBlock() {
        // given
        when(
            accessTokenBlockStore.isBlocked(USER_ID)
        ).thenReturn(true);

        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                USER_ID
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.BLOCKED
            );

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Redis 장애 시 DB의 활성 사용자는 인증을 허용한다")
    void resolve_allowsActiveUserWhenRedisFails() {
        // given
        when(
            accessTokenBlockStore.isBlocked(USER_ID)
        ).thenThrow(
            new QueryTimeoutException(
                "Redis unavailable"
            )
        );

        when(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    USER_ID
                )
        ).thenReturn(true);

        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                USER_ID
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.ALLOWED
            );
    }

    @Test
    @DisplayName("Redis 장애 시 DB의 탈퇴·잠금 사용자는 인증을 거부한다")
    void resolve_blocksInactiveUserWhenRedisFails() {
        // given
        when(
            accessTokenBlockStore.isBlocked(USER_ID)
        ).thenThrow(
            new QueryTimeoutException(
                "Redis unavailable"
            )
        );

        when(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    USER_ID
                )
        ).thenReturn(false);

        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                USER_ID
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.BLOCKED
            );
    }

    @Test
    @DisplayName("Redis와 DB가 모두 실패하면 인증 상태 확인 불가를 반환한다")
    void resolve_returnsUnavailableWhenRedisAndDatabaseFail() {
        // given
        when(
            accessTokenBlockStore.isBlocked(USER_ID)
        ).thenThrow(
            new QueryTimeoutException(
                "Redis unavailable"
            )
        );

        when(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    USER_ID
                )
        ).thenThrow(
            new QueryTimeoutException(
                "Database unavailable"
            )
        );

        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                USER_ID
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.UNAVAILABLE
            );
    }

    @Test
    @DisplayName("사용자 UUID가 없으면 인증을 거부한다")
    void resolve_blocksMissingUserId() {
        // when
        AccessTokenAuthenticationStatus status =
            accessTokenUserStatusService.resolve(
                null
            );

        // then
        assertThat(status)
            .isEqualTo(
                AccessTokenAuthenticationStatus.BLOCKED
            );

        verifyNoInteractions(
            accessTokenBlockStore,
            userRepository
        );
    }
}
