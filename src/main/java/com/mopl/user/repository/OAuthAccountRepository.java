package com.mopl.user.repository;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * OAuth 계정 연결 정보를 저장하고 조회하는 Repository
 *
 * <p>OAuth 로그인에서는 이메일이 아니라
 * {@code provider + providerUserId} 조합으로 기존 계정을 조회합니다.</p>
 */
public interface OAuthAccountRepository
    extends JpaRepository<OAuthAccount, UUID> {

    /**
     * OAuth Provider와 Provider 사용자 ID로 연결 계정을 조회
     *
     * @param provider       OAuth 인증 제공자
     * @param providerUserId Provider가 발급한 사용자 고유 식별자
     * @return 연결된 OAuth 계정, 존재하지 않으면 빈 Optional
     */
    @EntityGraph(attributePaths = "user")
    Optional<OAuthAccount> findByProviderAndProviderUserId(
        OAuthProvider provider,
        String providerUserId
    );

    /**
     * 특정 사용자가 해당 Provider 계정을 이미 연결했는지 확인
     *
     * @param userId   모두의 플리 사용자 UUID
     * @param provider OAuth 인증 제공자
     * @return 이미 연결되어 있으면 true
     */
    boolean existsByUserIdAndProvider(
        UUID userId,
        OAuthProvider provider
    );

    /**
     * 특정 사용자에게 연결된 모든 OAuth 계정을 조회
     *
     * @param userId 모두의 플리 사용자 UUID
     * @return 사용자에게 연결된 OAuth 계정 목록
     */
    List<OAuthAccount> findAllByUserId(UUID userId);
}
