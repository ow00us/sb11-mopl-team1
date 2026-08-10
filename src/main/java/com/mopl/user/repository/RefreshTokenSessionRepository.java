package com.mopl.user.repository;

import com.mopl.user.entity.RefreshTokenSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Refresh Token 로그인 세션을 PostgreSQL에 저장하고 조회하는 Repository
 *
 * JpaRepository를 상속하여 세션 저장, 단건 조회, 전체 조회, 삭제 등의
 * 기본적인 데이터 접근 기능을 사용할 수 있다.
 *
 * Refresh Token 원문은 이 Repository에 전달하지 않는다.
 * 토큰 원문을 SHA-256으로 변환한 해시값만 저장하고 조회해야 한다.
 */
public interface RefreshTokenSessionRepository
    extends JpaRepository<RefreshTokenSession, UUID> {

    /**
     * Refresh Token 해시와 일치하는 세션을 조회
     *
     * 나중에 Access Token 재발급 또는 로그아웃 요청이 들어오면
     * Cookie에서 받은 Refresh Token 원문을 먼저 SHA-256으로 해시하고,
     * 그 해시를 이용해 저장된 세션을 찾는다.
     *
     * Spring Data JPA가 메서드 이름을 분석하여 다음 형태의 쿼리를 생성
     *
     * SELECT *
     * FROM refresh_token_sessions
     * WHERE token_hash = ?
     *
     * @param tokenHash Refresh Token 원문의 SHA-256 해시
     * @return 일치하는 세션, 존재하지 않으면 빈 Optional
     */
    Optional<RefreshTokenSession> findByTokenHash(String tokenHash);

    /**
     * 특정 사용자가 보유한 모든 Refresh Token 세션을 조회
     *
     * 비밀번호 변경이나 관리자에 의한 계정 잠금 시
     * 해당 사용자의 모든 로그인 세션을 폐기하는 기능에서 사용
     *
     * 사용자 한 명이 여러 브라우저 또는 기기에서 로그인할 수 있으므로
     * 조회 결과는 단건이 아닌 List로 반환
     *
     * Spring Data JPA가 다음 형태의 쿼리를 생성
     *
     * SELECT *
     * FROM refresh_token_sessions
     * WHERE user_id = ?
     *
     * @param userId Refresh Token 세션을 소유한 사용자 UUID
     * @return 해당 사용자의 Refresh Token 세션 목록
     */
    List<RefreshTokenSession> findAllByUserId(UUID userId);
}
