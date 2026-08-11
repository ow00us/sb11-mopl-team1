package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.RefreshTokenProperties;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.RefreshTokenGenerator;
import com.mopl.user.security.RefreshTokenHasher;
import com.mopl.user.storage.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
}
