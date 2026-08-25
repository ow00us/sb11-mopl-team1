package com.mopl.user.security.oauth.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.config.OAuthLinkProperties;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.service.OAuthAccountManagementService;
import com.mopl.user.service.OAuthUserCreationService;
import com.mopl.user.service.OAuthUserProvisioningService;
import com.mopl.user.storage.RefreshTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 로컬 로그인 사용자에게 OAuth 계정을 연결하는 전체 내부 흐름을 검증
 *
 * <p>HTTP 세션에 연결 의도를 저장한 뒤 Provider 인증 결과를
 * OAuthUserResolutionService에 전달하여, 신규 사용자를 만들지 않고
 * 기존 사용자에게 OAuthAccount가 저장되는지 확인합니다.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    OAuthUserCreationService.class,
    OAuthUserProvisioningService.class,
    OAuthAccountManagementService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class OAuthAccountLinkIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OAuthUserProvisioningService provisioningService;

    @Autowired
    OAuthAccountManagementService accountManagementService;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    RefreshTokenStore refreshTokenStore;

    @BeforeEach
    void setUp() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("로컬 사용자의 OAuth 연결 의도를 소비해 Google 계정을 연결한다")
    void linkGoogleAccountToExistingLocalUser() {
        // given
        User localUser =
            User.builder()
                .email("local-user@example.com")
                .passwordHash("encoded-password")
                .name("로컬 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(
            localUser
        );

        /*
         * 실제 브라우저의 OAuth 왕복 과정과 동일하게
         * Mock HTTP 요청의 세션에 연결 의도를 저장
         */
        MockHttpServletRequest request =
            new MockHttpServletRequest();

        OAuthLinkProperties properties =
            new OAuthLinkProperties();

        properties.setIntentExpiration(
            Duration.ofMinutes(5)
        );

        OAuthLinkIntentSessionStore intentStore =
            new OAuthLinkIntentSessionStore(
                properties
            );

        intentStore.save(
            request,
            localUser.getId(),
            OAuthProvider.GOOGLE
        );

        assertThat(
            intentStore.hasPendingIntent(request)
        ).isTrue();

        /*
         * OAuthUserResolutionService가 현재 Callback 요청을 조회할 수 있도록
         * 테스트용 BeanFactory에 Mock HTTP 요청을 등록
         */
        StaticListableBeanFactory beanFactory =
            new StaticListableBeanFactory();

        beanFactory.addBean(
            "oauthCallbackRequest",
            request
        );

        ObjectProvider<HttpServletRequest>
            requestProvider =
            beanFactory.getBeanProvider(
                HttpServletRequest.class
            );

        OAuthUserResolutionService resolutionService =
            new OAuthUserResolutionService(
                provisioningService,
                accountManagementService,
                intentStore,
                requestProvider
            );

        // when
        User resolvedUser =
            resolutionService.resolve(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "different-google-email@example.com",
                "Google 사용자",
                "https://example.com/google-profile.png"
            );

        // then
        /*
         * OAuth 프로필 이메일과 관계없이 연결 의도에 기록된
         * 기존 로컬 사용자에게 연결되어야 한다.
         */
        assertThat(resolvedUser.getId())
            .isEqualTo(
                localUser.getId()
            );

        /*
         * 신규 OAuth 전용 사용자가 생성되면 안된다.
         */
        assertThat(userRepository.count())
            .isEqualTo(1);

        OAuthAccount linkedAccount =
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
                .orElseThrow();

        assertThat(
            linkedAccount
                .getUser()
                .getId()
        ).isEqualTo(
            localUser.getId()
        );

        assertThat(linkedAccount.getProvider())
            .isEqualTo(
                OAuthProvider.GOOGLE
            );

        assertThat(
            linkedAccount.getProviderUserId()
        ).isEqualTo(
            "google-sub-123"
        );

        /*
         * OAuth 연결 후에도 기존 로컬 로그인 수단은 유지되어야 한다.
         */
        User reloadedUser =
            userRepository
                .findById(
                    localUser.getId()
                )
                .orElseThrow();

        assertThat(
            reloadedUser.getPasswordHash()
        ).isEqualTo(
            "encoded-password"
        );

        /*
         * 연결 의도는 Callback에서 한 번 소비한 뒤 세션에서 제거
         */
        assertThat(
            intentStore.hasPendingIntent(request)
        ).isFalse();
    }
}
