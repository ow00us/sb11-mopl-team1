package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(
    properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=1000"
    }
)
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    OAuthUserCreationService.class,
    OAuthUserProvisioningService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class OAuthUserProvisioningConnectionPoolIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OAuthUserProvisioningService provisioningService;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void setUp() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("커넥션 풀이 하나여도 신규 OAuth 사용자를 생성한다")
    void resolveOrCreate_successWithSingleConnection() {
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "single-pool-google-sub",
                "user@example.com",
                "Google 사용자",
                null
            );

        assertThat(result.getId())
            .isNotNull();

        assertThat(userRepository.count())
            .isEqualTo(1);

        assertThat(oauthAccountRepository.count())
            .isEqualTo(1);
    }
}
