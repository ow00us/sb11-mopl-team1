package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.RefreshTokenProperties;
import com.mopl.user.entity.RefreshTokenSession;
import com.mopl.user.repository.RefreshTokenSessionRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.RefreshTokenGenerator;
import com.mopl.user.security.RefreshTokenHasher;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh Token의 발급과 서버 저장을 담당하는 Service
 *
 * 이 Service는 Refresh Token 원문을 생성한 뒤
 * 원문 자체가 아닌 SHA-256 해시만 PostgreSQL에 저장
 *
 * 로그인 API 및 HttpOnly Cookie와의 연동은 담당하지 않는다.
 * 후속 작업에서 로그인 성공 후 이 Service의 issue()를 호출하고
 * 반환된 원문을 Cookie에 담도록 연결
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final UserRepository userRepository;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenProperties refreshTokenProperties;

    /**
     * 주어진 사용자에게 새로운 Refresh Token을 발급
     *
     * 동작 순서
     *
     * 1. 사용자 UUID가 null인지 확인
     * 2. users 테이블에 사용자가 존재하는지 확인
     * 3. 256비트의 무작위 Refresh Token 원문을 생성
     * 4. 원문을 SHA-256 해시로 변환
     * 5. 설정된 만료 시간을 이용해 절대 만료 시각을 계산
     * 6. 사용자 UUID, 토큰 해시와 만료 시각을 DB에 저장
     * 7. 클라이언트에 전달할 원문과 만료 시각을 반환
     *
     * 하나의 사용자에게 여러 번 호출할 수 있으며,
     * 호출할 때마다 독립적인 Refresh Token 세션이 생성
     *
     * @param userId Refresh Token을 발급받을 사용자 UUID
     * @return Refresh Token 원문과 절대 만료 시각
     * @throws BusinessException 사용자 UUID가 null인 경우
     * @throws BusinessException 사용자가 존재하지 않는 경우
     */
    @Transactional
    public IssuedRefreshToken issue(UUID userId) {
        /*
         * null을 Repository에 전달하면 Spring Data JPA의
         * IllegalArgumentException이 발생할 수 있다.
         *
         * 애플리케이션의 일관된 오류 형식을 사용하기 위해
         * Repository 호출 전에 명시적으로 입력값을 검증
         */
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        /*
         * 존재하지 않는 사용자에게 Refresh Token 세션이 발급되지 않도록 한다.
         *
         * DB의 외래 키도 존재하지 않는 사용자 ID 저장을 차단하지만,
         * Service에서 먼저 확인하면 데이터 무결성 예외 대신
         * 사용자 조회 실패라는 도메인 의미를 가진 예외를 반환할 수 있다.
         */
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        /*
         * 클라이언트에 전달할 Refresh Token 원문을 생성
         *
         * 원문은 이 메서드의 반환값으로만 전달하며
         * 엔티티나 데이터베이스에는 저장하지 않는다.
         */
        String rawToken = refreshTokenGenerator.generate();

        /*
         * 데이터베이스 조회와 저장에 사용할 SHA-256 해시를 생성
         */
        String tokenHash = refreshTokenHasher.hash(rawToken);

        /*
         * Instant는 UTC 기준의 절대 시각을 표현
         *
         * application.yml에 설정된 기본 만료 시간 7일을
         * 현재 시각에 더해 절대 만료 시각을 계산
         */
        Instant expiresAt = Instant.now()
            .plus(refreshTokenProperties.getExpiration());

        RefreshTokenSession session =
            RefreshTokenSession.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .build();

        /*
         * 저장되는 값은 원문이 아닌 tokenHash
         *
         * @Transactional이 적용되어 있으므로 저장 과정에서 예외가 발생하면
         * Refresh Token 세션 저장 작업 전체가 롤백된다.
         */
        refreshTokenSessionRepository.save(session);

        /*
         * 이후 로그인 연동에서는 rawToken을 HttpOnly Cookie에 담는다.
         * expiresAt은 Cookie 만료 시각을 서버 세션과 일치시킬 때 사용
         */
        return new IssuedRefreshToken(
            rawToken,
            expiresAt
        );
    }
}
