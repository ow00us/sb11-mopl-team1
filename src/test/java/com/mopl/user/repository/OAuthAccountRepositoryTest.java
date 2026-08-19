package com.mopl.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OAuth 계정 저장 구조와 PostgreSQL 제약 조건을 검증
 *
 * <p>Testcontainers의 실제 PostgreSQL을 사용하여 JPA 매핑뿐 아니라
 * Flyway로 생성한 유일성 제약과 외래 키 삭제 정책까지 확인합니다.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OAuthAccountRepositoryTest {

    /**
     * 운영 환경과 같은 PostgreSQL의 제약 조건 동작을 검증
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    /**
     * OAuth 전용 사용자는 로컬 비밀번호가 없어도 저장할 수 있으며,
     * Provider의 고유 식별자로 다시 조회할 수 있어야 한다.
     */
    @Test
    @DisplayName("OAuth 계정을 Provider와 Provider 사용자 ID로 조회할 수 있다")
    void findByProviderAndProviderUserId_success() {
        // given
        User user = persistUser(
            "google-user@example.com",
            null
        );

        OAuthAccount account = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.GOOGLE)
            .providerUserId("google-sub-123")
            .build();

        entityManager.persistAndFlush(account);
        entityManager.clear();

        // when
        Optional<OAuthAccount> result =
            oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.GOOGLE,
                "google-sub-123"
            );

        // then
        assertThat(result).isPresent();

        OAuthAccount foundAccount = result.get();

        assertThat(foundAccount.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(foundAccount.getProviderUserId())
            .isEqualTo("google-sub-123");
        assertThat(foundAccount.getUser().getId())
            .isEqualTo(user.getId());
        assertThat(foundAccount.getUser().getPasswordHash())
            .isNull();
    }

    @Test
    @DisplayName("사용자에게 특정 Provider 계정이 연결되어 있는지 확인할 수 있다")
    void existsByUserIdAndProvider_success() {
        // given
        User user = persistUser(
            "linked-provider@example.com",
            null
        );

        OAuthAccount account = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.GOOGLE)
            .providerUserId("linked-google-id")
            .build();

        entityManager.persistAndFlush(account);
        entityManager.clear();

        // when
        boolean googleLinked =
            oauthAccountRepository.existsByUserIdAndProvider(
                user.getId(),
                OAuthProvider.GOOGLE
            );

        boolean kakaoLinked =
            oauthAccountRepository.existsByUserIdAndProvider(
                user.getId(),
                OAuthProvider.KAKAO
            );

        // then
        assertThat(googleLinked).isTrue();
        assertThat(kakaoLinked).isFalse();
    }

    /**
     * 동일한 외부 OAuth 계정이 서로 다른 서비스 사용자에게 연결되면
     * 계정 탈취나 사용자 식별 오류가 발생할 수 있으므로 DB에서 차단
     */
    @Test
    @DisplayName("동일한 Provider 계정을 여러 사용자에게 연결할 수 없다")
    void save_fail_whenProviderAccountAlreadyLinked() {
        // given
        User firstUser = persistUser(
            "first@example.com",
            null
        );
        User secondUser = persistUser(
            "second@example.com",
            null
        );

        OAuthAccount firstAccount = OAuthAccount.builder()
            .user(firstUser)
            .provider(OAuthProvider.GOOGLE)
            .providerUserId("same-google-sub")
            .build();

        entityManager.persistAndFlush(firstAccount);

        OAuthAccount duplicatedAccount = OAuthAccount.builder()
            .user(secondUser)
            .provider(OAuthProvider.GOOGLE)
            .providerUserId("same-google-sub")
            .build();

        // when & then
        /*
         * TestEntityManager를 직접 사용하면 Hibernate 예외가 그대로 발생하지만,
         * Repository를 통해 저장하면 Spring이 데이터 접근 예외를
         * DataIntegrityViolationException으로 변환
         */
        assertThatThrownBy(() ->
            oauthAccountRepository.saveAndFlush(duplicatedAccount)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 하나의 서비스 사용자에게 같은 Provider의 계정이 여러 개 연결되는 것을
     * user_id + provider 유일성 제약으로 차단
     */
    @Test
    @DisplayName("사용자에게 같은 Provider 계정을 중복 연결할 수 없다")
    void save_fail_whenUserAlreadyHasSameProvider() {
        // given
        User user = persistUser(
            "duplicate-provider@example.com",
            null
        );

        OAuthAccount firstAccount = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.KAKAO)
            .providerUserId("kakao-user-1")
            .build();

        entityManager.persistAndFlush(firstAccount);

        OAuthAccount duplicatedAccount = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.KAKAO)
            .providerUserId("kakao-user-2")
            .build();

        // when & then
        /*
         * user_id + provider 유일성 제약 위반이
         * Spring의 데이터 무결성 예외로 변환되는지 확인
         */
        assertThatThrownBy(() ->
            oauthAccountRepository.saveAndFlush(duplicatedAccount)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 같은 사용자라도 Provider가 다르면 여러 소셜 계정을 연결할 수 있다.
     */
    @Test
    @DisplayName("사용자는 서로 다른 Provider 계정을 연결할 수 있다")
    void save_success_whenProvidersAreDifferent() {
        // given
        User user = persistUser(
            "multi-provider@example.com",
            null
        );

        OAuthAccount googleAccount = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.GOOGLE)
            .providerUserId("google-user-id")
            .build();

        OAuthAccount kakaoAccount = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.KAKAO)
            .providerUserId("kakao-user-id")
            .build();

        OAuthAccount naverAccount = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.NAVER)
            .providerUserId("naver-user-id")
            .build();

        entityManager.persist(googleAccount);
        entityManager.persist(kakaoAccount);
        entityManager.persist(naverAccount);
        entityManager.flush();
        entityManager.clear();

        // when
        List<OAuthAccount> accounts =
            oauthAccountRepository.findAllByUserId(user.getId());

        // then
        assertThat(accounts)
            .extracting(OAuthAccount::getProvider)
            .containsExactlyInAnyOrder(
                OAuthProvider.GOOGLE,
                OAuthProvider.KAKAO,
                OAuthProvider.NAVER
            );
    }

    /**
     * 사용자가 삭제되면 외래 키의 ON DELETE CASCADE에 의해
     * 연결된 OAuth 계정도 함께 삭제되어야 합니다.
     */
    @Test
    @DisplayName("사용자 삭제 시 연결된 OAuth 계정도 삭제된다")
    void deleteUser_cascadesOAuthAccounts() {
        // given
        User user = persistUser(
            "deleted-user@example.com",
            null
        );

        OAuthAccount account = OAuthAccount.builder()
            .user(user)
            .provider(OAuthProvider.NAVER)
            .providerUserId("deleted-naver-id")
            .build();

        entityManager.persistAndFlush(account);

        UUID userId = user.getId();
        UUID accountId = account.getId();

        /*
         * 방금 저장한 OAuthAccount가 영속성 컨텍스트에 남아 있는 상태에서
         * User를 바로 remove하면, 관리 중인 OAuthAccount가 삭제된 User를
         * 계속 참조하여 TransientObjectException이 발생할 수 있다.
         *
         * 영속성 컨텍스트를 비운 뒤 User만 다시 조회하여 삭제하면
         * JPA 연관관계 처리의 개입 없이 실제 DB의 ON DELETE CASCADE를
         * 검증할 수 있다.
         */
        entityManager.clear();

        User reloadedUser =
            entityManager.find(User.class, userId);

        // when
        entityManager.remove(reloadedUser);
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(oauthAccountRepository.findById(accountId))
            .isEmpty();
    }

    /**
     * 테스트용 사용자를 실제 PostgreSQL에 저장
     *
     * @param email        사용자 이메일
     * @param passwordHash 로컬 비밀번호 해시, OAuth 전용 사용자는 null
     * @return 영속 상태의 사용자
     */
    private User persistUser(
        String email,
        String passwordHash
    ) {
        User user = User.builder()
            .email(email)
            .passwordHash(passwordHash)
            .name("OAuth 테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        entityManager.persistAndFlush(user);

        return user;
    }
}
