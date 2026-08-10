package com.mopl.user.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버에서 관리하는 Refresh Token 로그인 세션을 표현하는 JPA 엔티티
 *
 * Refresh Token 원문은 브라우저에 전달하고 서버에는 저장하지 않는다.
 * 서버에는 원문을 SHA-256으로 해시한 tokenHash만 저장하여
 * 데이터베이스가 노출되더라도 저장된 값이 Refresh Token으로 바로 사용되지 않도록 한다.
 *
 * 사용자 한 명이 여러 브라우저 또는 기기에서 로그인할 수 있으므로
 * 하나의 사용자 UUID에 여러 RefreshTokenSession이 저장될 수 있다.
 */
@Entity
@Getter
@Table(
    name = "refresh_token_sessions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_refresh_token_sessions_token_hash",
            columnNames = "token_hash"
        )
    },
    indexes = {
        @Index(
            name = "idx_refresh_token_sessions_user_id",
            columnList = "user_id"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenSession extends BaseEntity {

    /**
     * 이 Refresh Token 세션을 소유한 사용자의 UUID
     *
     * User 엔티티 객체를 직접 연관관계로 보관하지 않고 UUID만 저장
     * Refresh Token 조회 과정에서 불필요한 User 조회나 지연 로딩이
     * 발생하지 않도록 인증 세션과 사용자 엔티티의 결합을 줄임.
     *
     * 실제 참조 무결성은 Flyway 마이그레이션의 외래 키로 보장
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Refresh Token 원문을 SHA-256으로 해시한 값
     *
     * SHA-256 결과를 16진수 문자열로 표현하면 항상 64자가 되므로
     * 데이터베이스 컬럼의 길이도 64자로 제한
     *
     * 동일한 토큰 해시가 중복 저장되지 않도록 유일성 제약을 적용
     */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /**
     * Refresh Token 세션의 절대 만료 시각
     *
     * 기본 정책에서는 발급 시각으로부터 7일 뒤의 시각이 저장된다.
     * Access Token을 재발급하더라도 이 만료 시각은 자동으로 연장하지 않는다.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Refresh Token 세션이 폐기된 시각
     *
     * null이면 아직 명시적으로 폐기되지 않은 세션이고,
     * 값이 존재하면 로그아웃이나 보안 정책에 의해 폐기된 세션
     *
     * 실제 사용 가능 여부는 revokedAt뿐 아니라 expiresAt도 함께 확인해야 한다.
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Refresh Token 세션을 생성
     *
     * id, createdAt, updatedAt은 BaseEntity와 JPA Auditing이 자동으로 생성하므로
     * 생성자에서 직접 전달하지 않는다.
     *
     * @param userId     Refresh Token을 발급받은 사용자 UUID
     * @param tokenHash  Refresh Token 원문의 SHA-256 해시
     * @param expiresAt  Refresh Token의 절대 만료 시각
     */
    @Builder
    public RefreshTokenSession(
        UUID userId,
        String tokenHash,
        Instant expiresAt
    ) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revokedAt = null;
    }
}
