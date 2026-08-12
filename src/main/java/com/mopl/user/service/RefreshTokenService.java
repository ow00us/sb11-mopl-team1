package com.mopl.user.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 발급과 서버 측 저장을 담당하는 Service
 *
 * <p>이 Service는 Refresh Token 원문을 생성한 후 원문 자체는 저장하지 않고,
 * SHA-256 해시와 사용자 식별 정보를 Redis에 저장합니다.</p>
 *
 * <p>Redis 저장 데이터에는 Refresh Token 유효기간과 동일한 TTL을 적용하여
 * 만료된 세션이 자동으로 제거되도록 합니다.</p>
 *
 * <p>로그인 성공 시 {@link AuthService}가 {@link #issue(UUID)}를 호출하며,
 * 반환된 Refresh Token 원문은 Controller에서 HttpOnly Cookie로 전달합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final UserRepository userRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenProperties refreshTokenProperties;

    /**
     * Refresh Token 해시와 사용자 세션 정보를 저장하는 저장소
     *
     * 현재 구현체는 RedisRefreshTokenStore이며, Service는 Redis의 키 구조나
     * 명령어를 직접 알지 않고 저장소 인터페이스에만 의존
     */
    private final RefreshTokenStore refreshTokenStore;

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
     * 6. 사용자 UUID와 토큰 해시를 Redis에 TTL과 함께 저장
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
    public IssuedRefreshToken issue(UUID userId) {
        /*
         * 토큰을 어느 사용자에게 발급하는지 반드시 식별할 수 있어야 하므로
         * 사용자 UUID가 없는 요청은 즉시 거부
         */
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        /*
         * 존재하지 않는 사용자에게 Refresh Token 세션을 발급하지 않도록
         * 실제 users 테이블에 사용자가 존재하는지 확인
         */
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        /*
         * 외부에 전달할 Refresh Token 원문을 암호학적으로 안전한 난수로 생성
         */
        String rawToken = refreshTokenGenerator.generate();

        /*
         * 원문은 Redis에 저장하지 않고 SHA-256 해시값만 저장
         * 이후 재발급 요청에서도 Cookie로 받은 원문을 동일하게 해시하여 조회
         */
        String tokenHash = refreshTokenHasher.hash(rawToken);

        /*
         * 설정된 유효기간을 한 번만 조회하여 반환값의 만료 시각과
         * Redis 세션 TTL에 동일하게 적용
         *
         * 설정값을 각각 조회하면 설정 변경이나 테스트 구성에 따라
         * 반환 만료 시각과 실제 저장소 TTL이 달라질 수 있으므로
         * 하나의 Duration 값을 함께 사용
         */
        Duration expiration = refreshTokenProperties.getExpiration();
        Instant expiresAt = Instant.now().plus(expiration);

        /*
         * Refresh Token 원문은 클라이언트에게만 전달하고 서버 저장소에는 저장하지 않는다.
         *
         * 원문 대신 SHA-256 해시값을 저장하면 Redis 데이터가 노출되더라도
         * 저장된 값만으로 Refresh Token 원문을 바로 사용할 수 없다.
         *
         * expiration은 Redis TTL로 사용된다. 따라서 Refresh Token이 만료되면
         * Redis가 해당 세션 키를 자동으로 제거하며 별도의 만료 데이터 정리 작업이 필요 없다.
         */
        refreshTokenStore.save(
            userId,
            tokenHash,
            expiration
        );

        /*
         * 원문은 로그인 응답의 HttpOnly Cookie로 전달하기 위해 반환
         * 서버에는 원문을 저장하지 않고 해시값만 유지
         */
        return new IssuedRefreshToken(
            rawToken,
            expiresAt
        );
    }
}
