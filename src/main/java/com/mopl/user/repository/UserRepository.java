package com.mopl.user.repository;

import com.mopl.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID>, UserRepositoryCustom {
    // 정규화된 이메일로 사용자 조회

    /**
     * 다음과 같은 의미의 조회 쿼리를 자동으로 생성
     *
     * SELECT * FROM users WHERE email = ?
     */
    // 조회 결과 없을 수 있으므로 null 대신 optional로 반환
    // @Param email 앞뒤 공백 제거, 소문자로 변환된 이메일
    // @return 해당 이메일의 사용자, 존재하지 않으면 빈 Optional
    @Transactional(readOnly = true)
    Optional<User> findByEmail(String email);

    // 정규화된 이메일을 가진 사용자가 이미 존재하는지 확인
    // 회원가입 전에 중복 이메일 빠르게 확인하는데 사용
    @Transactional(readOnly = true)
    boolean existsByEmail(String email);

    /**
     * 탈퇴하지 않은 사용자를 정규화된 이메일로 조회
     *
     * 로그인·비밀번호 초기화 등 인증 경로에서 사용
     */
    @Transactional(readOnly = true)
    Optional<User> findByEmailAndDeletedAtIsNull(
        String email
    );

    /**
     * 탈퇴하지 않은 사용자를 UUID로 조회
     *
     * 토큰 발급·재발급 및 인증 상태 확인에 사용
     */
    @Transactional(readOnly = true)
    Optional<User> findByIdAndDeletedAtIsNull(
        UUID userId
    );

    /**
     * Access Token 인증을 계속 허용할 수 있는 사용자 여부를 확인
     *
     * <p>탈퇴했거나 관리자가 잠근 사용자는 JWT의 서명과 만료 시각이
     * 유효하더라도 기존 Access Token으로 인증할 수 없습니다.</p>
     *
     * @param userId Access Token의 subject에 저장된 사용자 UUID
     * @return 탈퇴하지 않았고 잠기지 않은 사용자이면 true
     */
    @Transactional(readOnly = true)
    boolean existsByIdAndLockedFalseAndDeletedAtIsNull(
        UUID userId
    );

    /**
     * 탈퇴하지 않은 사용자의 존재 여부를 확인
     */
    @Transactional(readOnly = true)
    boolean existsByIdAndDeletedAtIsNull(
        UUID userId
    );

    /**
     * 비밀번호 초기화 대상 활성 사용자를 이메일로 쓰기 잠금 조회
     *
     * 같은 사용자의 회원 탈퇴와 비밀번호 초기화를 직렬화하여
     * 탈퇴 익명화 결과가 뒤늦은 비밀번호 변경으로 덮이지 않도록 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT u "
            + "FROM User u "
            + "WHERE u.email = :email "
            + "AND u.deletedAt IS NULL"
    )
    Optional<User> findByEmailForUpdate(
        @Param("email")
        String email
    );

    /**
     * OAuth 연결 해제 정책을 검사할 사용자를 쓰기 잠금으로 조회
     *
     * <p>같은 사용자의 OAuth 연결을 동시에 해제하는 요청을 직렬화하여
     * 두 요청이 모두 마지막 로그인 수단을 삭제하는 경쟁 조건을 막습니다.</p>
     *
     * @param userId 잠금과 함께 조회할 사용자 UUID
     * @return 사용자, 존재하지 않으면 빈 Optional
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT u "
            + "FROM User u "
            + "WHERE u.id = :userId "
            + "AND u.deletedAt IS NULL"
    )
    Optional<User> findByIdForUpdate(
        @Param("userId")
        UUID userId
    );
}
