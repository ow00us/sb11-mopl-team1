package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

import com.mopl.global.security.JwtProperties;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.AccessTokenBlockStore;
import java.time.Duration;
import java.util.Optional;
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

    @Mock
    JwtProperties jwtProperties;

    @InjectMocks
    AccessTokenUserStatusService
        accessTokenUserStatusService;

    @Test
    @DisplayName("Redis에 허용 상태가 있으면 DB 조회 없이 인증을 허용한다")
    void resolve_allowsWhenRedisContainsAllowedState() {
        // given
        when(
            accessTokenBlockStore.findBlocked(USER_ID)
        ).thenReturn(Optional.of(false));

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
            accessTokenBlockStore.findBlocked(USER_ID)
        ).thenReturn(Optional.of(true));

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
            accessTokenBlockStore.findBlocked(USER_ID)
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
            accessTokenBlockStore.findBlocked(USER_ID)
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
            accessTokenBlockStore.findBlocked(USER_ID)
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

    @Test
    @DisplayName("Redis 캐시 미스 시 DB의 활성 사용자를 확인하고 허용 상태를 캐시한다")
    void resolve_allowsAndCachesActiveUserWhenRedisMisses() {
        // given
        Duration expiration =
            Duration.ofHours(3);

        when(
            accessTokenBlockStore.findBlocked(
                USER_ID
            )
        ).thenReturn(
            Optional.empty()
        );

        when(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    USER_ID
                )
        ).thenReturn(true);

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(expiration);

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

        verify(
            accessTokenBlockStore
        ).allowIfAbsent(
            USER_ID,
            expiration
        );
    }

    @Test
    @DisplayName("Redis 캐시 미스 시 DB의 탈퇴·잠금 사용자는 인증을 거부한다")
    void resolve_blocksInactiveUserWhenRedisMisses() {
        // given
        when(
            accessTokenBlockStore.findBlocked(
                USER_ID
            )
        ).thenReturn(
            Optional.empty()
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

        verifyNoInteractions(jwtProperties);
    }

    @Test
    @DisplayName("DB에서 활성 사용자를 확인하면 Redis 캐시 저장 실패에도 인증을 허용한다")
    void resolve_allowsActiveUserWhenAllowedCacheWriteFails() {
        // given
        Duration expiration =
            Duration.ofHours(3);

        when(
            accessTokenBlockStore.findBlocked(
                USER_ID
            )
        ).thenReturn(
            Optional.empty()
        );

        when(
            userRepository
                .existsByIdAndLockedFalseAndDeletedAtIsNull(
                    USER_ID
                )
        ).thenReturn(true);

        when(
            jwtProperties.getAccessTokenExpiration()
        ).thenReturn(expiration);

        doThrow(
            new QueryTimeoutException(
                "Redis unavailable"
            )
        ).when(
            accessTokenBlockStore
        ).allowIfAbsent(
            USER_ID,
            expiration
        );

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
}
