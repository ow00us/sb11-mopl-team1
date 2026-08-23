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
 * Naver OAuth2 Client 설정이 Authorization Code 인증 구성으로
 * 등록되는지 검증
 *
 * <p>Naver는 Spring Security가 기본 Provider 정보를 제공하지 않으므로
 * Authorization, Token, UserInfo 엔드포인트와 사용자 식별 속성을
 * 애플리케이션에서 직접 등록해야 합니다.</p>
 */
class NaverOAuth2ClientRegistrationTest {

    /**
     * 실제 Naver 네트워크를 호출하지 않고 Spring Boot OAuth2 Client
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
                "spring.security.oauth2.client.registration.naver.client-id="
                    + "test-naver-client-id",
                "spring.security.oauth2.client.registration.naver.client-secret="
                    + "test-naver-client-secret",
                "spring.security.oauth2.client.registration.naver."
                    + "client-authentication-method=client_secret_post",
                "spring.security.oauth2.client.registration.naver."
                    + "authorization-grant-type=authorization_code",
                "spring.security.oauth2.client.registration.naver."
                    + "redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
                "spring.security.oauth2.client.registration.naver."
                    + "scope=nickname,profile_image",
                "spring.security.oauth2.client.registration.naver."
                    + "client-name=Naver",
                "spring.security.oauth2.client.registration.naver."
                    + "provider=naver",
                "spring.security.oauth2.client.provider.naver."
                    + "authorization-uri=https://nid.naver.com/oauth2.0/authorize",
                "spring.security.oauth2.client.provider.naver."
                    + "token-uri=https://nid.naver.com/oauth2.0/token",
                "spring.security.oauth2.client.provider.naver."
                    + "user-info-uri=https://openapi.naver.com/v1/nid/me",
                "spring.security.oauth2.client.provider.naver."
                    + "user-name-attribute=response"
            );

    @Test
    @DisplayName("Naver ClientRegistration은 OAuth2 인증에 필요한 설정을 포함한다")
    void naverClientRegistration_containsOAuth2Configuration() {
        contextRunner.run(context -> {
            assertThat(context)
                .hasSingleBean(ClientRegistrationRepository.class);

            ClientRegistrationRepository repository =
                context.getBean(ClientRegistrationRepository.class);

            ClientRegistration naver =
                repository.findByRegistrationId("naver");

            assertThat(naver).isNotNull();
            assertThat(naver.getRegistrationId())
                .isEqualTo("naver");
            assertThat(naver.getClientName())
                .isEqualTo("Naver");
            assertThat(naver.getClientId())
                .isEqualTo("test-naver-client-id");
            assertThat(naver.getClientSecret())
                .isEqualTo("test-naver-client-secret");
            assertThat(naver.getClientAuthenticationMethod())
                .isEqualTo(
                    ClientAuthenticationMethod.CLIENT_SECRET_POST
                );
            assertThat(naver.getAuthorizationGrantType())
                .isEqualTo(
                    AuthorizationGrantType.AUTHORIZATION_CODE
                );
            assertThat(naver.getRedirectUri())
                .isEqualTo(
                    "{baseUrl}/login/oauth2/code/{registrationId}"
                );
            assertThat(naver.getScopes())
                .containsExactlyInAnyOrderElementsOf(
                    Set.of(
                        "nickname",
                        "profile_image"
                    )
                );

            assertThat(
                naver.getProviderDetails()
                    .getAuthorizationUri()
            ).isEqualTo(
                "https://nid.naver.com/oauth2.0/authorize"
            );

            assertThat(
                naver.getProviderDetails()
                    .getTokenUri()
            ).isEqualTo(
                "https://nid.naver.com/oauth2.0/token"
            );

            assertThat(
                naver.getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUri()
            ).isEqualTo(
                "https://openapi.naver.com/v1/nid/me"
            );

            /*
             * Naver 사용자 정보는 response 객체 안에 id와 프로필 정보가
             * 들어 있으므로 최상위 사용자 이름 속성은 response로 설정
             */
            assertThat(
                naver.getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUserNameAttributeName()
            ).isEqualTo("response");
        });
    }
}
