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
import com.mopl.user.security.FamilyRefreshToken;
import com.mopl.user.security.RefreshTokenFamilyCodec;
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
    RefreshTokenFamilyCodec refreshTokenFamilyCodec;

    @Mock
    RefreshTokenHasher refreshTokenHasher;

    @Mock
    RefreshTokenProperties refreshTokenProperties;

    @InjectMocks
    RefreshTokenService refreshTokenService;

    @Test
    @DisplayName("새 Family Refresh Token을 발급하고 해시와 만료 시각을 저장한다")
    void issue_success() {
        // given
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + "A".repeat(43);

        String tokenHash =
            "a".repeat(64);

        Duration expiration =
            Duration.ofDays(7);

        FamilyRefreshToken familyRefreshToken =
            new FamilyRefreshToken(
                familyId,
                rawToken
            );

        when(userRepository.existsById(userId))
            .thenReturn(true);

        /*
         * 로그인 시 새로운 Family ID와 Refresh Token 원문이
         * 생성되는 상황을 구성
         */
        when(
            refreshTokenFamilyCodec
                .generateNewFamily()
        ).thenReturn(
            familyRefreshToken
        );

        when(refreshTokenHasher.hash(rawToken))
            .thenReturn(tokenHash);

        when(
            refreshTokenProperties.getExpiration()
        ).thenReturn(expiration);

        /*
         * Service 내부에서 Instant.now()를 호출하므로
         * 호출 직전과 직후의 만료 시각 범위를 기록
         */
        Instant earliestExpiration =
            Instant.now()
                .plus(expiration);

        // when
        IssuedRefreshToken result =
            refreshTokenService.issue(userId);

        Instant latestExpiration =
            Instant.now()
                .plus(expiration);

        // then
        /*
         * 원문이 아닌 해시와 함께 사용자 UUID, Family ID와 TTL이
         * 저장소에 전달되는지 확인
         */
        verify(refreshTokenStore).save(
            userId,
            familyId,
            tokenHash,
            expiration
        );

        verifyNoMoreInteractions(
            refreshTokenStore
        );

        /*
         * 클라이언트에 전달할 결과에는 Family ID가 포함된
         * Refresh Token 원문이 들어 있어야 한다.
         */
        assertThat(result.rawToken())
            .isEqualTo(rawToken);

        assertThat(result.expiresAt())
            .isAfterOrEqualTo(
                earliestExpiration
            )
            .isBeforeOrEqualTo(
                latestExpiration
            );

        verify(userRepository)
            .existsById(userId);

        verify(refreshTokenFamilyCodec)
            .generateNewFamily();

        verify(refreshTokenHasher)
            .hash(rawToken);

        verify(refreshTokenProperties)
            .getExpiration();
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
            refreshTokenFamilyCodec,
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
            refreshTokenFamilyCodec,
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
    @DisplayName(
        "유효한 Refresh Token을 같은 Family의 새 토큰으로 교체한다"
    )
    void refresh_success() {
        // given
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String oldRawToken =
            familyId
                + "."
                + "A".repeat(43);

        String oldTokenHash =
            "a".repeat(64);

        String newRawToken =
            familyId
                + "."
                + "B".repeat(43);

        String newTokenHash =
            "b".repeat(64);

        String accessToken =
            "new-access-token";

        Duration expiration =
            Duration.ofDays(7);

        User user =
            createUser(
                userId,
                UserRole.ADMIN,
                false
            );

        /*
         * Cookie의 Refresh Token에서 Family ID가 정상적으로
         * 파싱되는 상황을 구성
         */
        when(
            refreshTokenFamilyCodec
                .parseFamilyId(oldRawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(oldRawToken)
        ).thenReturn(oldTokenHash);

        /*
         * Family ID와 현재 활성 tokenHash가 모두 일치하는
         * Redis 세션이 존재한다고 가정
         */
        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
        ).thenReturn(
            Optional.of(userId)
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        FamilyRefreshToken rotatedToken =
            new FamilyRefreshToken(
                familyId,
                newRawToken
            );

        /*
         * Rotation 이후에도 기존 Family ID를 유지하면서
         * 새로운 Secret을 가진 Token이 생성
         */
        when(
            refreshTokenFamilyCodec
                .generateForFamily(familyId)
        ).thenReturn(rotatedToken);

        when(
            refreshTokenHasher.hash(newRawToken)
        ).thenReturn(newTokenHash);

        when(
            refreshTokenProperties.getExpiration()
        ).thenReturn(expiration);

        when(
            jwtProvider.createAccessToken(
                userId,
                UserRole.ADMIN.name()
            )
        ).thenReturn(accessToken);

        when(
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).thenReturn(true);

        Instant earliestExpiration =
            Instant.now()
                .plus(expiration);

        // when
        RefreshResult result =
            refreshTokenService.refresh(
                oldRawToken
            );

        Instant latestExpiration =
            Instant.now()
                .plus(expiration);

        // then
        assertThat(
            result.jwtDto().accessToken()
        ).isEqualTo(accessToken);

        assertThat(
            result.jwtDto().userDto()
        ).isEqualTo(
            UserDto.from(user)
        );

        assertThat(
            result.issuedRefreshToken()
                .rawToken()
        ).isEqualTo(newRawToken);

        assertThat(
            result.issuedRefreshToken()
                .expiresAt()
        )
            .isAfterOrEqualTo(
                earliestExpiration
            )
            .isBeforeOrEqualTo(
                latestExpiration
            );

        verify(refreshTokenFamilyCodec)
            .parseFamilyId(oldRawToken);

        verify(refreshTokenHasher)
            .hash(oldRawToken);

        verify(refreshTokenStore)
            .findUserIdByFamilyAndTokenHash(
                familyId,
                oldTokenHash
            );

        verify(userRepository)
            .findById(userId);

        verify(refreshTokenFamilyCodec)
            .generateForFamily(familyId);

        verify(refreshTokenHasher)
            .hash(newRawToken);

        verify(refreshTokenProperties)
            .getExpiration();

        verify(jwtProvider)
            .createAccessToken(
                userId,
                UserRole.ADMIN.name()
            );

        verify(refreshTokenStore)
            .rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            );
    }

    @Test
    @DisplayName("Redis에 존재하지 않는 Family Refresh Token은 재발급에 실패한다")
    void refresh_failWhenTokenDoesNotExist() {
        // given
        UUID familyId =
            UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + "A".repeat(43);

        String tokenHash =
            "a".repeat(64);

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(rawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(rawToken)
        ).thenReturn(tokenHash);

        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    tokenHash
                )
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenFamilyCodec)
            .parseFamilyId(rawToken);

        verify(refreshTokenHasher)
            .hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByFamilyAndTokenHash(
                familyId,
                tokenHash
            );

        /*
         * Redis 세션을 찾지 못하면 사용자 조회와 새로운 토큰 발급,
         * JWT 발급 및 Rotation을 수행하면 안된다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenFamilyCodec,
            never()
        ).generateForFamily(
            org.mockito.ArgumentMatchers.any()
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
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
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + "A".repeat(43);

        String tokenHash =
            "a".repeat(64);

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(rawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(rawToken)
        ).thenReturn(tokenHash);

        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    tokenHash
                )
        ).thenReturn(
            Optional.of(userId)
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenFamilyCodec)
            .parseFamilyId(rawToken);

        verify(refreshTokenHasher)
            .hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByFamilyAndTokenHash(
                familyId,
                tokenHash
            );

        verify(userRepository)
            .findById(userId);

        verify(
            refreshTokenFamilyCodec,
            never()
        ).generateForFamily(
            org.mockito.ArgumentMatchers.any()
        );

        verifyNoInteractions(
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
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
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + "A".repeat(43);

        String tokenHash =
            "a".repeat(64);

        User lockedUser =
            createUser(
                userId,
                UserRole.USER,
                true
            );

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(rawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(rawToken)
        ).thenReturn(tokenHash);

        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    tokenHash
                )
        ).thenReturn(
            Optional.of(userId)
        );

        when(userRepository.findById(userId))
            .thenReturn(
                Optional.of(lockedUser)
            );

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(rawToken)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenFamilyCodec)
            .parseFamilyId(rawToken);

        verify(refreshTokenHasher)
            .hash(rawToken);

        verify(refreshTokenStore)
            .findUserIdByFamilyAndTokenHash(
                familyId,
                tokenHash
            );

        verify(userRepository)
            .findById(userId);

        verify(
            refreshTokenFamilyCodec,
            never()
        ).generateForFamily(
            org.mockito.ArgumentMatchers.any()
        );

        verifyNoInteractions(
            refreshTokenProperties,
            jwtProvider
        );

        verify(
            refreshTokenStore,
            never()
        ).rotate(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName("Access Token 발급에 실패하면 Family Token을 Rotation하지 않는다")
    void refresh_doesNotRotateWhenAccessTokenIssuanceFails() {
        // given
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String oldRawToken =
            familyId
                + "."
                + "A".repeat(43);

        String oldTokenHash =
            "a".repeat(64);

        String newRawToken =
            familyId
                + "."
                + "B".repeat(43);

        String newTokenHash =
            "b".repeat(64);

        Duration expiration =
            Duration.ofDays(7);

        User user =
            createUser(
                userId,
                UserRole.USER,
                false
            );

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(oldRawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(oldRawToken)
        ).thenReturn(oldTokenHash);

        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
        ).thenReturn(
            Optional.of(userId)
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(
            refreshTokenFamilyCodec
                .generateForFamily(familyId)
        ).thenReturn(
            new FamilyRefreshToken(
                familyId,
                newRawToken
            )
        );

        when(
            refreshTokenHasher.hash(newRawToken)
        ).thenReturn(newTokenHash);

        when(
            refreshTokenProperties.getExpiration()
        ).thenReturn(expiration);

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
            refreshTokenService.refresh(
                oldRawToken
            )
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "Access Token 발급 실패"
            );

        verify(jwtProvider)
            .createAccessToken(
                userId,
                UserRole.USER.name()
            );

        /*
         * JWT 생성에 실패하면 기존 Refresh Token Family 상태는
         * 그대로 유지돼야 재발급을 다시 시도할 수 있다.
         */
        verify(
            refreshTokenStore,
            never()
        ).rotate(
            userId,
            familyId,
            oldTokenHash,
            newTokenHash,
            expiration
        );
    }

    @Test
    @DisplayName("기존 Refresh Token이 먼저 소비되면 재발급에 실패한다")
    void refresh_failWhenRotationLosesRace() {
        // given
        UUID userId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String oldRawToken =
            familyId
                + "."
                + "A".repeat(43);

        String oldTokenHash =
            "a".repeat(64);

        String newRawToken =
            familyId
                + "."
                + "B".repeat(43);

        String newTokenHash =
            "b".repeat(64);

        Duration expiration =
            Duration.ofDays(7);

        User user =
            createUser(
                userId,
                UserRole.USER,
                false
            );

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(oldRawToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenHasher.hash(oldRawToken)
        ).thenReturn(oldTokenHash);

        when(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
        ).thenReturn(
            Optional.of(userId)
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(
            refreshTokenFamilyCodec
                .generateForFamily(familyId)
        ).thenReturn(
            new FamilyRefreshToken(
                familyId,
                newRawToken
            )
        );

        when(
            refreshTokenHasher.hash(newRawToken)
        ).thenReturn(newTokenHash);

        when(
            refreshTokenProperties.getExpiration()
        ).thenReturn(expiration);

        when(
            jwtProvider.createAccessToken(
                userId,
                UserRole.USER.name()
            )
        ).thenReturn(
            "unused-access-token"
        );

        /*
         * 다른 재발급 또는 로그아웃 요청이 먼저 Family 세션을
         * 변경하거나 폐기한 상황을 표현
         */
        when(
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(
                oldRawToken
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenStore)
            .rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            );

        verify(jwtProvider)
            .createAccessToken(
                userId,
                UserRole.USER.name()
            );
    }

    @Test
    @DisplayName("형식이 잘못된 Refresh Token은 Redis 조회 전에 거부한다")
    void refresh_failWhenTokenFormatIsInvalid() {
        // given
        String invalidRawToken =
            "invalid-refresh-token";

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(invalidRawToken)
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            refreshTokenService.refresh(
                invalidRawToken
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenFamilyCodec)
            .parseFamilyId(invalidRawToken);

        /*
         * Family ID조차 확인할 수 없는 입력은 해시하거나
         * Redis에 전달하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
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
            refreshTokenFamilyCodec,
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
            refreshTokenFamilyCodec,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token Family 세션을 폐기한다")
    void signOut_revokesCurrentRefreshTokenFamily() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        /*
         * Refresh Token 원문은
         * {familyId}.{무작위 Secret} 형식으로 구성된다.
         *
         * 로그아웃에서는 Secret의 해시를 이용하지 않고,
         * Rotation 전후에도 유지되는 familyId를 이용해 현재 활성 세션을 폐기
         */
        String rawRefreshToken =
            familyId
                + "."
                + "A".repeat(43);

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(rawRefreshToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        when(
            refreshTokenStore.revoke(
                authenticatedUserId,
                familyId
            )
        ).thenReturn(true);

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            rawRefreshToken
        );

        // then
        verify(refreshTokenFamilyCodec)
            .parseFamilyId(rawRefreshToken);

        verify(refreshTokenStore)
            .revoke(
                authenticatedUserId,
                familyId
            );

        /*
         * 로그아웃은 Access Token에서 확인한 사용자 UUID와
         * Refresh Token에서 추출한 Family ID만 사용
         *
         * 따라서 사용자 DB 조회, Refresh Token 해싱,
         * 만료 시간 조회 및 JWT 발급은 실행하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenHasher,
            refreshTokenProperties,
            jwtProvider
        );
    }

    @Test
    @DisplayName("이미 폐기된 Refresh Token Family로 로그아웃해도 정상 종료한다")
    void signOut_succeedsWhenFamilyIsAlreadyRevoked() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String rawRefreshToken =
            familyId
                + "."
                + "B".repeat(43);

        when(
            refreshTokenFamilyCodec
                .parseFamilyId(rawRefreshToken)
        ).thenReturn(
            Optional.of(familyId)
        );

        /*
         * Redis에 해당 Family가 없거나 이미 폐기된 경우
         * revoke()는 false를 반환
         *
         * 로그아웃은 여러 번 요청해도 같은 결과를 내는 멱등 작업이므로
         * 이 경우에도 예외를 발생시키지 않는다.
         */
        when(
            refreshTokenStore.revoke(
                authenticatedUserId,
                familyId
            )
        ).thenReturn(false);

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            rawRefreshToken
        );

        // then
        verify(refreshTokenFamilyCodec)
            .parseFamilyId(rawRefreshToken);

        verify(refreshTokenStore)
            .revoke(
                authenticatedUserId,
                familyId
            );

        verifyNoInteractions(
            userRepository,
            refreshTokenHasher,
            refreshTokenProperties,
            jwtProvider
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
        /*
         * Cookie가 없는 경우 Redis에서 폐기할 Family를 식별할 수 없다.
         * 이미 로그아웃된 상태와 동일하게 취급하고 정상 종료
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenFamilyCodec,
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
            refreshTokenFamilyCodec,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenStore,
            jwtProvider
        );
    }

    @Test
    @DisplayName("Refresh Token 형식이 잘못되어도 Cookie 삭제를 위해 로그아웃을 정상 종료한다")
    void signOut_succeedsWhenRefreshTokenFormatIsInvalid() {
        // given
        UUID authenticatedUserId = UUID.randomUUID();
        String invalidRefreshToken = "invalid-refresh-token";

        /*
         * 토큰 형식이 잘못되어 Family ID를 추출하지 못하는 상황
         */
        when(
            refreshTokenFamilyCodec
                .parseFamilyId(invalidRefreshToken)
        ).thenReturn(Optional.empty());

        // when
        refreshTokenService.signOut(
            authenticatedUserId,
            invalidRefreshToken
        );

        // then
        verify(refreshTokenFamilyCodec)
            .parseFamilyId(invalidRefreshToken);

        /*
         * Family ID를 식별할 수 없으므로 Redis 세션은 건드리지 않는다.
         *
         * 서비스는 예외를 발생시키지 않고 정상 종료하고,
         * Controller가 만료된 Refresh Token Cookie를 응답에 담아 삭제
         */
        verifyNoInteractions(
            userRepository,
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
                UUID.randomUUID()
                    + "."
                    + "A".repeat(43)
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        /*
         * 인증 사용자 확인이 가장 먼저 수행되어야 함.
         *
         * 인증되지 않은 요청에서는 Refresh Token 파싱이나
         * Redis 세션 폐기를 포함한 후속 작업을 실행하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            refreshTokenFamilyCodec,
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
