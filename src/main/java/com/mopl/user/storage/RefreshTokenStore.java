package com.mopl.user.storage;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Refresh Token Family 세션 저장소가 제공해야 하는 기능을 정의
 *
 * <p>Refresh Token 원문은 저장소에 전달하지 않고,
 * SHA-256으로 변환한 해시만 전달합니다.</p>
 *
 * <p>로그인 시 생성한 familyId는 해당 로그인 세션이 유지되는 동안
 * Rotation 이후에도 변경되지 않습니다. 실제 Refresh Token의 Secret과
 * tokenHash만 Rotation마다 새 값으로 변경됩니다.</p>
 *
 * <p>Service는 이 인터페이스만 의존하며 Redis Key, Hash, Set과
 * Lua Script 같은 저장 기술의 세부사항은 구현체가 담당합니다.</p>
 */
public interface RefreshTokenStore {

    /**
     * 새로운 Refresh Token Family 세션을 저장
     *
     * <p>familyId를 안정적인 세션 식별자로 사용하고,
     * 해당 Family의 현재 활성 Refresh Token 해시와 사용자 UUID를
     * 함께 저장합니다.</p>
     *
     * <p>expiration은 Family 세션 Key와 사용자별 세션 인덱스의
     * TTL에 사용합니다.</p>
     *
     * @param userId Refresh Token을 발급받은 사용자 UUID
     * @param familyId 로그인 세션을 식별하는 안정적인 Family UUID
     * @param tokenHash 현재 활성 Refresh Token 원문의 SHA-256 해시
     * @param expiration Refresh Token Family 세션의 유효 기간
     */
    void save(
        UUID userId,
        UUID familyId,
        String tokenHash,
        Duration expiration
    );

    /**
     * Family ID와 현재 Refresh Token 해시가 모두 일치하는 세션의
     * 사용자 UUID를 조회
     *
     * <p>Family ID만 일치하고 tokenHash가 다르면 이미 Rotation으로
     * 소비된 이전 Refresh Token일 수 있으므로 유효한 세션으로
     * 인정하지 않습니다.</p>
     *
     * <p>세션이 없거나 만료됐거나 현재 활성 tokenHash와 일치하지 않으면
     * 빈 Optional을 반환합니다.</p>
     *
     * @param familyId Refresh Token 원문에서 추출한 Family UUID
     * @param tokenHash 전달받은 Refresh Token 원문의 SHA-256 해시
     * @return 세션 소유 사용자 UUID, 유효하지 않으면 빈 Optional
     */
    Optional<UUID> findUserIdByFamilyAndTokenHash(
        UUID familyId,
        String tokenHash
    );

    /**
     * 기존 Refresh Token을 같은 Family에 속한 새로운 Token으로
     * 원자적으로 교체
     *
     * <p>Family 세션에 저장된 사용자 UUID와 현재 활성 tokenHash가
     * 각각 userId 및 oldTokenHash와 일치할 때만 newTokenHash로
     * 교체합니다.</p>
     *
     * <p>familyId는 Rotation 전후에 변경되지 않습니다. 따라서
     * 로그아웃 요청은 Rotation 이전 Cookie를 전달하더라도 같은
     * Family의 현재 활성 세션을 식별할 수 있습니다.</p>
     *
     * @param userId Refresh Token Family 세션의 사용자 UUID
     * @param familyId Rotation 전후에 유지할 Family UUID
     * @param oldTokenHash 재발급에 사용된 기존 Refresh Token 해시
     * @param newTokenHash 새로 발급할 Refresh Token 해시
     * @param expiration 갱신할 Family 세션의 유효 기간
     * @return 교체 성공 시 true, 세션 또는 현재 해시가 다르면 false
     */
    boolean rotate(
        UUID userId,
        UUID familyId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    );

    /**
     * 특정 사용자의 Refresh Token Family 세션을 원자적으로 폐기
     *
     * <p>로그아웃 Cookie에 포함된 Family ID를 사용하므로,
     * Cookie의 tokenHash가 Rotation 이전 값이더라도 해당 Family에
     * 현재 저장된 활성 Refresh Token 세션을 제거할 수 있습니다.</p>
     *
     * <p>Family 세션에 저장된 사용자 UUID가 인증된 사용자 UUID와
     * 일치하는 경우에만 삭제합니다. 따라서 다른 사용자가 임의의
     * Family ID를 전달해도 해당 세션을 폐기할 수 없습니다.</p>
     *
     * @param userId 인증된 Refresh Token 세션 소유 사용자 UUID
     * @param familyId 폐기할 로그인 세션 Family UUID
     * @return 세션 폐기 시 true, 세션이 없거나 소유자가 다르면 false
     */
    boolean revoke(
        UUID userId,
        UUID familyId
    );

    /**
     * 특정 사용자가 보유한 모든 Refresh Token Family 세션을 폐기
     *
     * <p>비밀번호 변경, 비밀번호 초기화, 계정 잠금 또는 권한 변경처럼
     * 기존 로그인 세션을 더 이상 신뢰할 수 없는 보안 상태 변경 시
     * 해당 사용자의 모든 로그인 세션을 한 번에 제거합니다.</p>
     *
     * <p>구현체는 개별 Family를 애플리케이션 반복문으로 삭제하지 않고,
     * 가능한 한 저장소 내부에서 원자적으로 처리해야 합니다. 이를 통해
     * 일부 Family만 삭제된 상태가 외부에 노출되는 것을 방지합니다.</p>
     *
     * @param userId 모든 Refresh Token 세션을 폐기할 사용자 UUID
     * @return 실제로 폐기한 Refresh Token Family 세션 수
     */
    long revokeAllByUserId(
        UUID userId
    );

    /**
     * 특정 사용자가 보유한 현재 활성 Refresh Token Family ID를 조회
     *
     * <p>비밀번호 변경, 계정 잠금과 전체 로그아웃 기능에서
     * 해당 사용자의 모든 로그인 세션을 폐기할 때 사용할 수 있습니다.</p>
     *
     * <p>구현체는 이미 TTL이 만료된 Family를 인덱스에서 정리한 뒤
     * 실제 Family 세션 Key가 존재하는 ID만 반환해야 합니다.</p>
     *
     * @param userId 조회할 사용자 UUID
     * @return 현재 활성 상태인 Family UUID 집합
     */
    Set<UUID> findFamilyIdsByUserId(
        UUID userId
    );
}
