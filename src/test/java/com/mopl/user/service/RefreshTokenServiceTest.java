package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.config.RefreshTokenProperties;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.RefreshTokenGenerator;
import com.mopl.user.security.RefreshTokenHasher;
import com.mopl.user.storage.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RefreshTokenService의 Refresh Token 발급 규칙을 검증하는 단위 테스트
 *
 * <p>실제 Redis 명령과 TTL 동작은 RedisRefreshTokenStore 통합 테스트에서
 * 검증합니다. 이 테스트에서는 사용자 확인, 원문 생성, 해시 처리,
 * 만료 시각 계산과 RefreshTokenStore에 전달되는 값을 검증합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    UserRepository userRepository;

    /**
     * Refresh Token 재발급 성공 시 새로운 Access Token이
     * 발급되는지 검증하기 위한 Mock
     */
    @Mock
    JwtProvider jwtProvider;

    /**
     * 실제 Redis에 연결하지 않고 RefreshTokenService가 저장소에 전달하는
     * 사용자 UUID, 토큰 해시, TTL을 검증하기 위한 Mock
     *
     * Redis 명령과 실제 TTL 동작은 RedisRefreshTokenStore 통합 테스트에서
     * 별도로 검증
     */
    @Mock
    RefreshTokenStore refreshTokenStore;

    @Mock
    RefreshTokenGenerator refreshTokenGenerator;

    @Mock
    RefreshTokenHasher refreshTokenHasher;

    @Mock
    RefreshTokenProperties refreshTokenProperties;

    @InjectMocks
    RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("Refresh Token 원문을 발급하고 해시와 만료 시각을 저장한다")
    void issue_success() {
        // given
        UUID userId = UUID.randomUUID();
        String rawToken = "generated-refresh-token";
        String tokenHash = "a".repeat(64);
        Duration expiration = Duration.ofDays(7);

        when(userRepository.existsById(userId))
            .thenReturn(true);

        when(refreshTokenGenerator.generate())
            .thenReturn(rawToken);

        when(refreshTokenHasher.hash(rawToken))
            .thenReturn(tokenHash);

        when(refreshTokenProperties.getExpiration())
            .thenReturn(expiration);

        /*
         * Instant.now()는 Service 내부에서 호출되므로 정확히 같은 시각을
         * 테스트에서 미리 알 수 없다.
         *
         * Service 호출 직전과 직후의 허용 범위를 기록하여
         * expiresAt이 그 사이에서 계산됐는지 검증
         */
        Instant earliestExpiration =
            Instant.now().plus(expiration);

        // when
        IssuedRefreshToken result =
            refreshTokenService.issue(userId);

        Instant latestExpiration =
            Instant.now().plus(expiration);

        // then
        /*
         * Service가 Refresh Token 원문이 아니라 SHA-256 해시값을
         * 사용자 UUID와 설정된 유효기간과 함께 저장소에 전달하는지 검증한다.
         */
        verify(refreshTokenStore).save(
            userId,
            tokenHash,
            expiration
        );

        /*
         * 검증한 해시 저장 외에 Refresh Token 원문 등을 이용한
         * 추가 저장 호출이 없었는지 확인
         */
        verifyNoMoreInteractions(refreshTokenStore);

        /*
         * 클라이언트에게 전달할 결과에는 생성된 Refresh Token 원문이 포함되어야 한다.
         * 원문은 Redis 저장소에는 전달되지 않고 반환값을 통해서만 외부로 전달
         */
        assertThat(result.rawToken())
            .isEqualTo(rawToken);

        /*
         * Service 내부에서 Instant.now()를 호출하므로 테스트에서 정확히 같은
         * Instant 값을 미리 만들 수 없다.
         *
         * 따라서 Service 호출 직전과 직후에 계산한 만료 시각 범위 안에
         * 실제 반환 만료 시각이 포함되는지 검증
         */
        assertThat(result.expiresAt())
            .isAfterOrEqualTo(earliestExpiration)
            .isBeforeOrEqualTo(latestExpiration);

        verify(userRepository).existsById(userId);
        verify(refreshTokenGenerator).generate();
        verify(refreshTokenHasher).hash(rawToken);
        verify(refreshTokenProperties).getExpiration();
    }

    @Test
    @DisplayName("존재하지 않는 사용자에게는 Refresh Token을 발급하지 않는다")
    void issue_failWhenUserDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();

        when(userRepository.existsById(userId))
            .thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.issue(userId)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).existsById(userId);

        /*
         * 사용자가 존재하지 않으면 원문 생성과 해시 처리,
         * Redis 저장이 모두 수행되지 않아야 한다.
         */
        verifyNoInteractions(
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("사용자 UUID가 null이면 Refresh Token을 발급하지 않는다")
    void issue_failWhenUserIdIsNull() {
        assertThatThrownBy(() ->
            refreshTokenService.issue(null)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        /*
         * null 검증은 Repository 호출 전에 수행되므로
         * 어떠한 의존 객체도 호출되지 않아야 한다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("발급 결과 문자열에는 Refresh Token 원문을 노출하지 않는다")
    void issuedRefreshToken_doesNotExposeRawTokenInToString() {
        // given
        String rawToken = "sensitive-refresh-token";

        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                rawToken,
                Instant.parse("2026-08-17T00:00:00Z")
            );

        // when
        String result = issuedRefreshToken.toString();

        // then
        assertThat(result)
            .doesNotContain(rawToken)
            .contains("rawToken=***")
            .contains("2026-08-17T00:00:00Z");
    }

    @Test
    @DisplayName("유효한 Refresh Token을 새 토큰으로 교체하고 Access Token을 발급한다")
    void refresh_success() {
        // given
        UUID userId = UUID.randomUUID();

        String oldRawToken = "old-refresh-token";
        String oldTokenHash = "a".repeat(64);

        String newRawToken = "new-refresh-token";
        String newTokenHash = "b".repeat(64);

        String accessToken = "new-access-token";
        Duration expiration = Duration.ofDays(7);

        User user = createUser(
            userId,
            UserRole.ADMIN,
            false
        );

        /*
         * Cookie로 받은 기존 Refresh Token 원문을 해시
         */
        when(refreshTokenHasher.hash(oldRawToken))
            .thenReturn(oldTokenHash);

        /*
         * Redis에 기존 세션이 존재하며 해당 세션의 소유자는
         * userId라고 가정
         */
        when(
            refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
        ).thenReturn(Optional.of(userId));

        /*
         * Redis에서 확인한 사용자 UUID로 현재 사용자 정보를 조회
         */
        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        /*
         * Rotation에 사용할 새로운 Refresh Token을 생성하고 해시
         */
        when(refreshTokenGenerator.generate())
            .thenReturn(newRawToken);

        when(refreshTokenHasher.hash(newRawToken))
            .thenReturn(newTokenHash);

        when(refreshTokenProperties.getExpiration())
            .thenReturn(expiration);

        /*
         * 기존 세션과 새로운 세션의 원자적 교체가 성공한다고 가정
         */
        when(
            refreshTokenStore.rotate(
                userId,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).thenReturn(true);

        /*
         * 사용자의 현재 역할이 ADMIN이므로 새 Access Token에도
         * ADMIN 역할이 반영
         */
        when(
            jwtProvider.createAccessToken(
                userId,
                UserRole.ADMIN.name()
            )
        ).thenReturn(accessToken);

        Instant earliestExpiration =
            Instant.now().plus(expiration);

        // when
        RefreshResult result =
            refreshTokenService.refresh(oldRawToken);

        Instant latestExpiration =
            Instant.now().plus(expiration);

        // then
        assertThat(result.jwtDto().accessToken())
            .isEqualTo(accessToken);

        assertThat(result.jwtDto().userDto())
            .isEqualTo(UserDto.from(user));

        assertThat(result.issuedRefreshToken().rawToken())
            .isEqualTo(newRawToken);

        assertThat(result.issuedRefreshToken().expiresAt())
            .isAfterOrEqualTo(earliestExpiration)
            .isBeforeOrEqualTo(latestExpiration);

        verify(refreshTokenHasher).hash(oldRawToken);

        verify(refreshTokenStore)
            .findUserIdByTokenHash(oldTokenHash);

        verify(userRepository).findById(userId);
        verify(refreshTokenGenerator).generate();
        verify(refreshTokenHasher).hash(newRawToken);
        verify(refreshTokenProperties).getExpiration();

        verify(refreshTokenStore).rotate(
            userId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        verify(jwtProvider).createAccessToken(
            userId,
            UserRole.ADMIN.name()
        );
    }

    @Test
    @DisplayName("Redis에 존재하지 않는 Refresh Token은 재발급에 실패한다")
    void refresh_failWhenTokenDoesNotExist() {
        // given
        String rawToken = "unknown-refresh-token";
        String tokenHash = "a".repeat(64);

        when(refreshTokenHasher.hash(rawToken))
            .thenReturn(tokenHash);

        when(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenHasher).hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByTokenHash(tokenHash);

        /*
         * 기존 Refresh Token 세션을 찾지 못했으므로 사용자 조회,
         * 신규 토큰 생성, Rotation 및 Access Token 발급은 실행되면 안 된다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("Refresh Token의 사용자가 존재하지 않으면 재발급에 실패한다")
    void refresh_failWhenUserDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();
        String rawToken = "deleted-user-refresh-token";
        String tokenHash = "a".repeat(64);

        when(refreshTokenHasher.hash(rawToken))
            .thenReturn(tokenHash);

        when(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenHasher).hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByTokenHash(tokenHash);

        verify(userRepository).findById(userId);

        /*
         * 사용자가 삭제된 상태이므로 새로운 토큰은 생성하거나 저장하면 안 된다.
         */
        verifyNoInteractions(
            refreshTokenGenerator,
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("잠긴 계정은 Refresh Token으로 토큰을 재발급할 수 없다")
    void refresh_failWhenUserIsLocked() {
        // given
        UUID userId = UUID.randomUUID();
        String rawToken = "locked-user-refresh-token";
        String tokenHash = "a".repeat(64);

        User lockedUser = createUser(
            userId,
            UserRole.USER,
            true
        );

        when(refreshTokenHasher.hash(rawToken))
            .thenReturn(tokenHash);

        when(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(lockedUser));

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenHasher).hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByTokenHash(tokenHash);

        verify(userRepository).findById(userId);

        /*
         * 계정 잠금이 확인된 이후에는 새로운 Refresh Token 생성과 Access Token 발급이 진행되면 안 된다.
         */
        verifyNoInteractions(
            refreshTokenGenerator,
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("Access Token 발급에 실패하면 기존 Refresh Token을 소비하지 않는다")
    void refresh_doesNotRotateWhenAccessTokenIssuanceFails() {
        // given
        UUID userId = UUID.randomUUID();

        String oldRawToken = "old-refresh-token";
        String oldTokenHash = "a".repeat(64);

        String newRawToken = "new-refresh-token";
        String newTokenHash = "b".repeat(64);

        Duration expiration = Duration.ofDays(7);

        User user = createUser(
            userId,
            UserRole.USER,
            false
        );

        when(refreshTokenHasher.hash(oldRawToken))
            .thenReturn(oldTokenHash);

        when(
            refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
        ).thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(refreshTokenGenerator.generate())
            .thenReturn(newRawToken);

        when(refreshTokenHasher.hash(newRawToken))
            .thenReturn(newTokenHash);

        when(refreshTokenProperties.getExpiration())
            .thenReturn(expiration);

        /*
         * JWT 발급 과정에서 예외가 발생하는 상황을 만든다.
         */
        when(
            jwtProvider.createAccessToken(
                userId,
                UserRole.USER.name()
            )
        ).thenThrow(
            new IllegalStateException(
                "Access Token 발급 실패"
            )
        );

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(oldRawToken)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Access Token 발급 실패");

        verify(jwtProvider).createAccessToken(
            userId,
            UserRole.USER.name()
        );

        /*
         * Access Token 발급에 실패했으므로 기존 Refresh Token을
         * 폐기하는 Redis Rotation은 절대로 실행되면 안 된다.
         *
         * 이를 통해 사용자는 일시적인 JWT 발급 오류가 해결된 뒤
         * 기존 Refresh Token으로 재발급을 다시 시도할 수 있다.
         */
        verify(
            refreshTokenStore,
            never()
        ).rotate(
            userId,
            oldTokenHash,
            newTokenHash,
            expiration
        );
    }

    @Test
    @DisplayName("기존 Refresh Token이 먼저 소비되면 재발급에 실패한다")
    void refresh_failWhenRotationLosesRace() {
        // given
        UUID userId = UUID.randomUUID();

        String oldRawToken = "old-refresh-token";
        String oldTokenHash = "a".repeat(64);

        String newRawToken = "new-refresh-token";
        String newTokenHash = "b".repeat(64);

        Duration expiration = Duration.ofDays(7);

        User user = createUser(
            userId,
            UserRole.USER,
            false
        );

        when(refreshTokenHasher.hash(oldRawToken))
            .thenReturn(oldTokenHash);

        when(
            refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
        ).thenReturn(Optional.of(userId));

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(refreshTokenGenerator.generate())
            .thenReturn(newRawToken);

        when(refreshTokenHasher.hash(newRawToken))
            .thenReturn(newTokenHash);

        when(refreshTokenProperties.getExpiration())
            .thenReturn(expiration);

        /*
         * 외부 상태를 변경하기 전에 Access Token 생성까지는
         * 정상적으로 완료된 상황을 가정
         */
        when(
            jwtProvider.createAccessToken(
                userId,
                UserRole.USER.name()
            )
        ).thenReturn("unused-access-token");

        /*
         * 조회 직후 다른 요청이 먼저 기존 Refresh Token을 소비한 상황을 표현
         */
        when(
            refreshTokenStore.rotate(
                userId,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(oldRawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenStore).rotate(
            userId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        /*
         * Access Token은 Redis 상태를 변경하기 전에 생성되지만,
         * Rotation이 실패하면 RefreshResult가 반환되지 않으므로
         * 클라이언트에는 전달되지 않는다.
         */
        verify(jwtProvider).createAccessToken(
            userId,
            UserRole.USER.name()
        );
    }

    @Test
    @DisplayName("Refresh Token이 null이면 재발급에 실패한다")
    void refresh_failWhenTokenIsNull() {
        assertThatThrownBy(() ->
            refreshTokenService.refresh(null)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("Refresh Token이 공백이면 재발급에 실패한다")
    void refresh_failWhenTokenIsBlank() {
        assertThatThrownBy(() ->
            refreshTokenService.refresh("   ")
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("로그아웃 시 현재 사용자의 Refresh Token 세션을 폐기한다")
    void signOut_revokesCurrentRefreshTokenSession() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();
        String rawRefreshToken = "current-refresh-token";
        String tokenHash = "a".repeat(64);

        when(
            refreshTokenHasher.hash(rawRefreshToken)
        ).thenReturn(tokenHash);

        when(
            refreshTokenStore.revoke(
                authenticatedUserId,
                tokenHash
            )
        ).thenReturn(true);

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            rawRefreshToken
        );

        // then
        verify(refreshTokenHasher)
            .hash(rawRefreshToken);

        verify(refreshTokenStore)
            .revoke(
                authenticatedUserId,
                tokenHash
            );

        /*
         * 로그아웃은 Access Token 인증 결과와 Redis 세션만 사용하므로
         * 사용자 DB 조회, 새 토큰 생성과 JWT 발급은 수행하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenProperties,
            jwtProvider
        );
    }

    @Test
    @DisplayName("이미 폐기된 Refresh Token으로 로그아웃해도 정상 종료한다")
    void signOut_succeedsWhenSessionIsAlreadyRevoked() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();
        String rawRefreshToken = "already-revoked-token";
        String tokenHash = "b".repeat(64);

        when(
            refreshTokenHasher.hash(rawRefreshToken)
        ).thenReturn(tokenHash);

        /*
         * 세션이 이미 만료되거나 폐기되어 실제 삭제할 값이 없는 상황
         */
        when(
            refreshTokenStore.revoke(
                authenticatedUserId,
                tokenHash
            )
        ).thenReturn(false);

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            rawRefreshToken
        );

        // then
        /*
         * revoke()가 false여도 예외 없이 종료되어
         * 반복 로그아웃의 멱등성이 유지되어야 한다.
         */
        verify(refreshTokenHasher)
            .hash(rawRefreshToken);

        verify(refreshTokenStore)
            .revoke(
                authenticatedUserId,
                tokenHash
            );
    }

    @Test
    @DisplayName("Refresh Token Cookie가 없으면 저장소를 호출하지 않고 로그아웃한다")
    void signOut_succeedsWithoutRefreshTokenCookie() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            null
        );

        // then
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("Refresh Token Cookie가 공백이면 저장소를 호출하지 않고 로그아웃한다")
    void signOut_succeedsWithBlankRefreshTokenCookie() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            "   "
        );

        // then
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("인증 사용자 UUID가 없으면 로그아웃에 실패한다")
    void signOut_failsWhenAuthenticatedUserIdIsNull() {
        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.signOut(
                null,
                "refresh-token"
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        /*
         * 인증 사용자 확인이 가장 먼저 수행되어야 하므로
         * 토큰 해시와 Redis 접근을 포함한 어떤 작업도 실행하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    /**
     * Refresh Token 재발급 테스트에 사용할 사용자 엔티티를 생성
     *
     * <p>User의 UUID는 실제 저장 시 JPA가 생성하지만 이 테스트는
     * Repository Mock을 사용하는 단위 테스트이므로 ReflectionTestUtils로
     * 테스트용 UUID를 설정합니다.</p>
     *
     * @param userId 테스트에서 사용할 사용자 UUID
     * @param role 현재 사용자 역할
     * @param locked 계정 잠금 여부
     * @return 재발급 테스트용 사용자 엔티티
     */
    private User createUser(
        UUID userId,
        UserRole role,
        boolean locked
    ) {
        User user =
            User.builder()
                .email("user@example.com")
                .passwordHash("encoded-password")
                .name("테스트 사용자")
                .role(role)
                .locked(locked)
                .build();

        ReflectionTestUtils.setField(
            user,
            "id",
            userId
        );

        return user;
    }
}
