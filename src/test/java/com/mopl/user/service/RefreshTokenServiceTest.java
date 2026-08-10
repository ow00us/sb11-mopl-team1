package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.RefreshTokenProperties;
import com.mopl.user.entity.RefreshTokenSession;
import com.mopl.user.repository.RefreshTokenSessionRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.RefreshTokenGenerator;
import com.mopl.user.security.RefreshTokenHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RefreshTokenService의 발급 규칙을 검증하는 단위 테스트
 *
 * 실제 PostgreSQL 저장 동작은 RefreshTokenSessionRepositoryTest에서 검증했으므로
 * 이 테스트에서는 사용자 확인, 원문 생성, 해시 처리, 만료 시각 계산과
 * Repository에 전달되는 엔티티의 값을 검증
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    RefreshTokenSessionRepository refreshTokenSessionRepository;

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
        ArgumentCaptor<RefreshTokenSession> sessionCaptor =
            ArgumentCaptor.forClass(RefreshTokenSession.class);

        verify(refreshTokenSessionRepository)
            .save(sessionCaptor.capture());

        RefreshTokenSession savedSession =
            sessionCaptor.getValue();

        assertThat(savedSession.getUserId())
            .isEqualTo(userId);

        assertThat(savedSession.getTokenHash())
            .isEqualTo(tokenHash);

        /*
         * DB에 저장되는 값이 Refresh Token 원문이 아니라
         * 해시라는 사실을 명시적으로 검증
         */
        assertThat(savedSession.getTokenHash())
            .isNotEqualTo(rawToken);

        assertThat(savedSession.getExpiresAt())
            .isAfterOrEqualTo(earliestExpiration)
            .isBeforeOrEqualTo(latestExpiration);

        assertThat(savedSession.getRevokedAt())
            .isNull();

        /*
         * 외부로 반환하는 값은 원문이어야 하며,
         * 만료 시각은 DB에 저장한 값과 정확히 같아야 한다.
         */
        assertThat(result.rawToken())
            .isEqualTo(rawToken);

        assertThat(result.expiresAt())
            .isEqualTo(savedSession.getExpiresAt());

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
         * DB 저장이 모두 수행되지 않아야 한다.
         */
        verifyNoInteractions(
            refreshTokenGenerator,
            refreshTokenHasher,
            refreshTokenProperties,
            refreshTokenSessionRepository
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
            refreshTokenSessionRepository
        );
    }
}
