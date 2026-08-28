package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.config.RefreshTokenProperties;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.FamilyRefreshToken;
import com.mopl.user.security.RefreshTokenFamilyCodec;
import com.mopl.user.security.RefreshTokenHasher;
import com.mopl.user.storage.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
    // 사용자 UUID와 역할을 기반으로 새 Access Token 발급
    private final JwtProvider jwtProvider;
    // Family ID가 포함된 Refresh Token을 생성하고 Cookie의 Token에서 Family ID를 추출하는 Codec
    private final RefreshTokenFamilyCodec refreshTokenFamilyCodec;
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
     * @throws BusinessException 사용자가 존재하지 않거나 탈퇴한 경우
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
         * 존재하지 않거나 탈퇴한 사용자에게 Refresh Token 세션을
         * 발급하지 않도록 활성 사용자 여부를 확인
         */
        if (!userRepository.existsByIdAndDeletedAtIsNull(userId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        /*
         * 로그인할 때마다 새로운 Family ID와 무작위 Secret으로
         * 첫 번째 Refresh Token을 생성
         *
         * Family ID는 해당 로그인 세션 동안 유지되고,
         * Secret과 tokenHash만 Rotation마다 변경
         */
        FamilyRefreshToken familyRefreshToken =
            refreshTokenFamilyCodec
                .generateNewFamily();

        UUID familyId =
            familyRefreshToken.familyId();

        String rawToken =
            familyRefreshToken.rawToken();

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
            familyId,
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

    /**
     * 기존 Refresh Token을 검증하고 새로운 Access Token과
     * Refresh Token을 재발급
     *
     * <p>Refresh Token Rotation 정책에 따라 재발급에 사용된 기존 토큰은
     * 즉시 폐기하고 새로운 Refresh Token 세션으로 교체합니다.</p>
     *
     * <p>동작 순서</p>
     *
     * <ol>
     *     <li>Cookie로 전달된 기존 Refresh Token 원문을 확인합니다.</li>
     *     <li>Token 원문에서 로그인 세션 Family ID를 추출합니다.</li>
     *     <li>기존 Token 원문 전체를 SHA-256으로 해시합니다.</li>
     *     <li>Family ID와 현재 해시가 일치하는 Redis 세션의 사용자 UUID를 조회합니다.</li>
     *     <li>데이터베이스에서 사용자를 조회하고 계정 상태를 확인합니다.</li>
     *     <li>같은 Family ID와 새로운 Secret으로 Refresh Token을 생성합니다.</li>
     *     <li>Access Token과 응답 객체를 생성합니다.</li>
     *     <li>Redis Lua Script로 현재 활성 해시를 새로운 해시로 교체합니다.</li>
     *     <li>새 Access Token과 Refresh Token 발급 결과를 반환합니다.</li>
     * </ol>
     *
     * @param rawRefreshToken Cookie로 전달된 기존 Refresh Token 원문
     * @return 새 Access Token, 사용자 정보 및 새 Refresh Token 발급 결과
     * @throws BusinessException Refresh Token이 없거나 유효하지 않은 경우
     * @throws BusinessException 토큰의 사용자가 존재하지 않거나 잠긴 경우
     * @throws BusinessException 기존 토큰이 이미 사용되어 Rotation에 실패한 경우
     */
    public RefreshResult refresh(
        String rawRefreshToken
    ) {
        /*
         * Refresh Token은 인증 자격 증명이므로 값이 없거나 공백이면
         * 유효하지 않은 인증 정보로 처리
         *
         * Cookie 자체가 누락된 요청은 이후 Controller의 @CookieValue에서
         * 먼저 400 Bad Request로 처리되고, 이 검사는 Service를 직접
         * 호출하는 경우와 공백 Cookie에 대한 방어선 역할을 함.
         */
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "유효하지 않은 Refresh Token입니다."
            );
        }

        /*
         * Refresh Token 원문에서 로그인 세션 Family ID를 추출
         *
         * UUID, 구분자 또는 Secret 형식이 올바르지 않으면
         * Redis를 조회하지 않고 유효하지 않은 인증 정보로 처리
         */
        UUID familyId =
            refreshTokenFamilyCodec
                .parseFamilyId(rawRefreshToken)
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "유효하지 않은 Refresh Token입니다."
                    )
                );

        /*
         * Redis에는 Refresh Token 원문을 저장하지 않고 SHA-256 해시만
         * 저장하므로, Cookie로 받은 원문을 동일한 방식으로 해시
         */
        String oldTokenHash =
            refreshTokenHasher.hash(rawRefreshToken);

        /*
         * 기존 Refresh Token 해시로 Redis 세션의 소유자 UUID를 조회
         *
         * Key가 없다는 것은 다음 중 하나를 의미
         *
         * 1. 존재하지 않는 Refresh Token
         * 2. 이미 만료된 Refresh Token
         * 3. Rotation으로 이미 사용된 Refresh Token
         * 4. 로그아웃 또는 강제 폐기된 Refresh Token
         *
         * 클라이언트에는 내부 원인을 구분해서 노출하지 않고 모두 401로 처리
         */
        UUID userId =
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "유효하지 않은 Refresh Token입니다."
                    )
                );

        /*
         * Access Token에는 현재 사용자의 역할이 포함되어야 하므로
         * Redis에 저장된 UUID를 기준으로 users 테이블을 한 번 조회
         *
         * 사용자 삭제 여부와 현재 역할, 현재 계정 잠금 상태를
         * 토큰이 처음 발급됐던 시점이 아니라 재발급 시점 기준으로 반영
         */
        User user = userRepository
                .findByIdAndDeletedAtIsNull(
                    userId
                )
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "유효하지 않은 Refresh Token입니다."
                    )
                );

        /*
         * 계정이 잠긴 이후 기존 Refresh Token만으로 인증을 계속 유지하지
         * 못하도록 재발급을 거부
         */
        if (user.isLocked()) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "잠긴 계정은 토큰을 재발급할 수 없습니다."
            );
        }

        /*
         * 기존 로그인 세션의 Family ID를 유지하면서
         * 새로운 무작위 Secret을 가진 Refresh Token을 생성
         *
         * Rotation 전후 Token은 원문과 해시는 달라지지만
         * 같은 Family ID를 공유
         */
        FamilyRefreshToken rotatedRefreshToken =
            refreshTokenFamilyCodec
                .generateForFamily(familyId);

        String newRawToken =
            rotatedRefreshToken.rawToken();

        /*
         * 새 Refresh Token도 원문을 저장하지 않고
         * 전체 원문의 SHA-256 해시만 Redis에 저장
         */
        String newTokenHash =
            refreshTokenHasher.hash(newRawToken);

        /*
         * 새 Refresh Token의 Redis TTL과 Cookie 만료 시각이
         * 동일한 설정값을 사용하도록 Duration을 한 번만 조회
         */
        Duration expiration =
            refreshTokenProperties.getExpiration();

        Instant expiresAt =
            Instant.now().plus(expiration);

        /*
         * Redis 세션을 교체하기 전에 Access Token을 먼저 생성
         *
         * Rotation을 먼저 수행한 뒤 JWT 발급이 실패하면 기존 Refresh Token은
         * 이미 폐기되고, Redis에 저장된 새 Refresh Token은 클라이언트가
         * 전달받지 못하는 상태가 된다.
         *
         * 따라서 응답에 필요한 값들을 먼저 안전하게 생성하고,
         * 외부 상태를 변경하는 Redis Rotation을 마지막에 수행
         */
        String accessToken =
            jwtProvider.createAccessToken(
                user.getId(),
                user.getRole().name()
            );

        /*
         * Access Token과 현재 사용자 정보는 JSON 응답에 사용할
         * JwtDto로 구성
         */
        JwtDto jwtDto =
            new JwtDto(
                UserDto.from(user),
                accessToken
            );

        /*
         * 새로운 Refresh Token 원문은 JSON에 포함하지 않고
         * Controller에서 HttpOnly Cookie로 만들 수 있도록 분리
         */
        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                newRawToken,
                expiresAt
            );

        /*
         * JWT 발급과 응답 객체 구성이 모두 성공한 다음,
         * 기존 Refresh Token 세션과 신규 세션을 원자적으로 교체
         *
         * 이 호출 이전에 예외가 발생하면 Redis는 변경되지 않으므로
         * 사용자는 기존 Refresh Token으로 다시 재발급을 요청할 수 있다.
         */
        boolean rotated =
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            );

        /*
         * 기존 토큰 조회 이후 다른 요청이 먼저 해당 토큰을 소비했다면
         * rotate()가 false를 반환
         *
         * 이때 미리 만들어진 Access Token은 클라이언트에 반환되지 않으므로
         * 인증 수단으로 사용될 수 없다.
         */
        if (!rotated) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "이미 사용되었거나 유효하지 않은 Refresh Token입니다."
            );
        }

        return new RefreshResult(
            jwtDto,
            issuedRefreshToken
        );
    }

    /**
     * 인증된 사용자의 현재 Refresh Token Family 세션을 폐기
     *
     * <p>로그아웃 요청에 Refresh Token Cookie가 포함되어 있다면
     * Token 원문에서 로그인 세션의 Family ID를 추출한 뒤,
     * 인증된 사용자 UUID와 함께 RefreshTokenStore에 전달합니다.</p>
     *
     * <p>로그아웃은 요청 Cookie의 tokenHash를 기준으로 세션을 삭제하지
     * 않습니다. Rotation 전후에도 동일하게 유지되는 Family ID를 사용하므로,
     * 재발급이 먼저 완료된 경우에도 해당 Family의 현재 활성 Refresh Token
     * 세션을 폐기할 수 있습니다.</p>
     *
     * <p>Cookie가 없거나 비어 있거나 형식이 잘못된 경우와
     * 이미 만료·폐기된 Family는 이미 로그아웃된 상태와 동일하게 취급하여
     * 예외를 발생시키지 않습니다. 이를 통해 로그아웃의 멱등성을
     * 유지합니다.</p>
     *
     * <p>저장소는 Family에 저장된 사용자 UUID와 인증된 사용자 UUID가
     * 일치하는 경우에만 세션을 폐기합니다. 따라서 다른 사용자가 소유한
     * Refresh Token Family는 삭제할 수 없습니다.</p>
     *
     * @param authenticatedUserId Access Token 인증 사용자 UUID
     * @param rawRefreshToken REFRESH_TOKEN Cookie 원문, 없으면 null
     * @throws BusinessException 인증 사용자 UUID가 없는 경우
     */
    public void signOut(
        UUID authenticatedUserId,
        String rawRefreshToken
    ) {
        /*
         * 로그아웃 API는 기존 OpenAPI 계약에 따라 유효한 Access Token이
         * 필요한 보호 API
         *
         * 실제 HTTP 요청에서는 SecurityFilterChain이 인증되지 않은 요청을
         * 먼저 차단하지만, Service를 직접 호출하거나 인증 정보 전달에
         * 문제가 생기는 경우를 대비해 Service에서도 확인
         */
        if (authenticatedUserId == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        /*
         * Refresh Token Cookie가 없거나 공백이면 Redis에서 폐기할
         * 현재 기기 세션이 없는 상태
         *
         * 이미 로그아웃된 사용자에게 다시 로그아웃을 요청해도
         * 동일하게 성공하도록 아무 작업 없이 종료
         */
        if (
            rawRefreshToken == null
                || rawRefreshToken.isBlank()
        ) {
            return;
        }

        /*
         * 로그아웃 Cookie에서 Family ID를 추출
         *
         * 잘못된 형식의 Cookie라면 Redis에서 식별할 수 있는 세션이 없으므로
         * 별도 예외 없이 종료
         *
         * Controller는 Service 결과와 관계없이 삭제 Cookie를 반환하므로
         * 클라이언트의 잘못된 Cookie도 제거
         */
        Optional<UUID> familyId =
            refreshTokenFamilyCodec
                .parseFamilyId(rawRefreshToken);

        if (familyId.isEmpty()) {
            return;
        }

        /*
         * tokenHash가 아닌 안정적인 Family ID를 기준으로
         * 해당 로그인 세션의 현재 활성 Refresh Token을 폐기
         *
         * 재발급 요청이 먼저 Rotation을 완료했더라도 Family ID는
         * 변경되지 않으므로 새 tokenHash가 저장된 Family Key를 삭제
         *
         * 반환값이 false인 경우는 이미 폐기됐거나 다른 사용자의
         * Family이지만 로그아웃 멱등성을 위해 예외를 발생시키지 않음
         */
        refreshTokenStore.revoke(
            authenticatedUserId,
            familyId.get()
        );
    }
}
