package com.mopl.user.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 OAuth 계정과 모두의 플리 사용자를 연결하는 엔티티
 *
 * <p>OAuth 사용자는 이메일이 아니라 Provider가 제공하는 고유 사용자 ID로
 * 식별합니다. 따라서 {@code provider + providerUserId} 조합이 하나의
 * 외부 계정을 나타냅니다.</p>
 *
 * <p>한 명의 서비스 사용자는 Google, Kakao, Naver 계정을 각각 연결할 수
 * 있지만 같은 Provider의 계정을 여러 개 연결할 수는 없습니다.</p>
 *
 * <p>OAuth Access Token이나 Refresh Token은 이 엔티티에 저장하지 않습니다.
 * 이 엔티티에는 외부 계정의 식별 정보만 저장합니다.</p>
 */
@Entity
@Getter
@Table(
    name = "oauth_accounts",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_oauth_accounts_provider_user",
            columnNames = {"provider", "provider_user_id"}
        ),
        @UniqueConstraint(
            name = "uk_oauth_accounts_user_provider",
            columnNames = {"user_id", "provider"}
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthAccount extends BaseEntity {

    /**
     * OAuth 계정이 연결된 모두의 플리 사용자
     *
     * <p>OAuth 계정을 조회할 때마다 사용자 정보를 항상 함께 조회할 필요는
     * 없으므로 LAZY 로딩을 사용합니다.</p>
     *
     * <p>OAuth 계정을 다른 사용자에게 옮기는 것은 계정 탈취나 잘못된 연결을
     * 유발할 수 있으므로 생성 이후 변경하지 않습니다.</p>
     */
    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "user_id",
        nullable = false,
        updatable = false
    )
    private User user;

    /**
     * 외부 인증 제공자
     *
     * <p>enum 순서가 변경되어도 기존 데이터의 의미가 유지되도록
     * 문자열로 저장합니다.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 20,
        updatable = false
    )
    private OAuthProvider provider;

    /**
     * OAuth Provider가 발급한 변경되지 않는 사용자 고유 식별자
     *
     * <p>Google의 sub, Kakao의 id, Naver의 id에 해당
     * 이메일이나 닉네임은 변경될 수 있으므로 이 필드를 대신할 수 없습니다.</p>
     */
    @Column(
        name = "provider_user_id",
        nullable = false,
        length = 255,
        updatable = false
    )
    private String providerUserId;

    /**
     * OAuth 계정 연결 정보를 생성합니다.
     *
     * @param user           연결할 모두의 플리 사용자
     * @param provider       OAuth 인증 제공자
     * @param providerUserId Provider가 발급한 사용자 고유 식별자
     * @throws IllegalArgumentException 사용자, Provider 또는 Provider 사용자 ID가
     *                                  유효하지 않은 경우
     */
    @Builder
    public OAuthAccount(
        User user,
        OAuthProvider provider,
        String providerUserId
    ) {
        if (user == null) {
            throw new IllegalArgumentException(
                "OAuth 계정을 연결할 사용자는 필수입니다."
            );
        }

        if (provider == null) {
            throw new IllegalArgumentException(
                "OAuth Provider는 필수입니다."
            );
        }

        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException(
                "OAuth Provider 사용자 ID는 필수입니다."
            );
        }

        if (providerUserId.length() > 255) {
            throw new IllegalArgumentException(
                "OAuth Provider 사용자 ID는 255자를 초과할 수 없습니다."
            );
        }

        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
