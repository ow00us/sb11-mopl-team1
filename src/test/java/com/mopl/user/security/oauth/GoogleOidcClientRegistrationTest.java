package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Google OAuth Client 설정이 OIDC 인증 구성으로 등록되는지 검증
 *
 * <p>Google 로그인은 OAuth 2.0 Authorization Code 흐름을 사용하면서
 * openid scope를 통해 OIDC 사용자 인증을 수행합니다.</p>
 */
class GoogleOidcClientRegistrationTest {

    /**
     * 실제 Google 네트워크를 호출하지 않고 Spring Boot OAuth2 Client
     * 자동 설정 결과만 검증
     */
    private final WebApplicationContextRunner contextRunner =
        new WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SecurityAutoConfiguration.class,
                    OAuth2ClientAutoConfiguration.class
                )
            )
            .withPropertyValues(
                "spring.security.oauth2.client.registration.google.client-id="
                    + "test-google-client-id",
                "spring.security.oauth2.client.registration.google.client-secret="
                    + "test-google-client-secret",
                "spring.security.oauth2.client.registration.google."
                    + "client-authentication-method=client_secret_basic",
                "spring.security.oauth2.client.registration.google."
                    + "authorization-grant-type=authorization_code",
                "spring.security.oauth2.client.registration.google."
                    + "redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
                "spring.security.oauth2.client.registration.google."
                    + "scope=openid,profile,email",
                "spring.security.oauth2.client.registration.google."
                    + "client-name=Google"
            );

    @Test
    @DisplayName("Google ClientRegistration은 OIDC 인증에 필요한 설정을 포함한다")
    void googleClientRegistration_containsOidcConfiguration() {
        contextRunner.run(context -> {
            assertThat(context)
                .hasSingleBean(ClientRegistrationRepository.class);

            ClientRegistrationRepository repository =
                context.getBean(ClientRegistrationRepository.class);

            ClientRegistration google =
                repository.findByRegistrationId("google");

            assertThat(google).isNotNull();
            assertThat(google.getRegistrationId())
                .isEqualTo("google");
            assertThat(google.getClientId())
                .isEqualTo("test-google-client-id");
            assertThat(google.getClientSecret())
                .isEqualTo("test-google-client-secret");
            assertThat(google.getClientAuthenticationMethod())
                .isEqualTo(
                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                );
            assertThat(google.getAuthorizationGrantType())
                .isEqualTo(
                    AuthorizationGrantType.AUTHORIZATION_CODE
                );
            assertThat(google.getRedirectUri())
                .isEqualTo(
                    "{baseUrl}/login/oauth2/code/{registrationId}"
                );
            assertThat(google.getScopes())
                .containsExactlyInAnyOrderElementsOf(
                    Set.of(
                        "openid",
                        "profile",
                        "email"
                    )
                );

            /*
             * Spring Boot의 Google 기본 Provider 설정은 사용자를
             * 이메일이 아닌 OIDC subject(sub)로 식별
             */
            assertThat(
                google.getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUserNameAttributeName()
            ).isEqualTo("sub");

            /*
             * Google ID Token 서명을 검증할 공개키 주소가 등록되어야
             * OIDC 인증 결과를 신뢰할 수 있다.
             */
            assertThat(
                google.getProviderDetails()
                    .getJwkSetUri()
            ).isNotBlank();
        });
    }
}
