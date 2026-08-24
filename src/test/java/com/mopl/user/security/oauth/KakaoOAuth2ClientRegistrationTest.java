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
 * Kakao OAuth2 Client 설정이 Authorization Code 인증 구성으로
 * 등록되는지 검증
 *
 * <p>Kakao는 Google과 달리 Spring Security가 기본 Provider 정보를
 * 제공하지 않으므로 Authorization, Token, UserInfo 엔드포인트와 사용자
 * 식별 속성을 애플리케이션에서 직접 등록해야 합니다.</p>
 */
class KakaoOAuth2ClientRegistrationTest {

    /**
     * 실제 Kakao 네트워크를 호출하지 않고 Spring Boot OAuth2 Client
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
                "spring.security.oauth2.client.registration.kakao.client-id="
                    + "test-kakao-client-id",
                "spring.security.oauth2.client.registration.kakao.client-secret="
                    + "test-kakao-client-secret",
                "spring.security.oauth2.client.registration.kakao."
                    + "client-authentication-method=client_secret_post",
                "spring.security.oauth2.client.registration.kakao."
                    + "authorization-grant-type=authorization_code",
                "spring.security.oauth2.client.registration.kakao."
                    + "redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
                "spring.security.oauth2.client.registration.kakao."
                    + "scope=profile_nickname,profile_image",
                "spring.security.oauth2.client.registration.kakao."
                    + "client-name=Kakao",
                "spring.security.oauth2.client.registration.kakao."
                    + "provider=kakao",
                "spring.security.oauth2.client.provider.kakao."
                    + "authorization-uri=https://kauth.kakao.com/oauth/authorize",
                "spring.security.oauth2.client.provider.kakao."
                    + "token-uri=https://kauth.kakao.com/oauth/token",
                "spring.security.oauth2.client.provider.kakao."
                    + "user-info-uri=https://kapi.kakao.com/v2/user/me",
                "spring.security.oauth2.client.provider.kakao."
                    + "user-name-attribute=id"
            );

    @Test
    @DisplayName("Kakao ClientRegistration은 OAuth2 인증에 필요한 설정을 포함한다")
    void kakaoClientRegistration_containsOAuth2Configuration() {
        contextRunner.run(context -> {
            assertThat(context)
                .hasSingleBean(ClientRegistrationRepository.class);

            ClientRegistrationRepository repository =
                context.getBean(ClientRegistrationRepository.class);

            ClientRegistration kakao =
                repository.findByRegistrationId("kakao");

            assertThat(kakao).isNotNull();
            assertThat(kakao.getRegistrationId())
                .isEqualTo("kakao");
            assertThat(kakao.getClientName())
                .isEqualTo("Kakao");
            assertThat(kakao.getClientId())
                .isEqualTo("test-kakao-client-id");
            assertThat(kakao.getClientSecret())
                .isEqualTo("test-kakao-client-secret");
            assertThat(kakao.getClientAuthenticationMethod())
                .isEqualTo(
                    ClientAuthenticationMethod.CLIENT_SECRET_POST
                );
            assertThat(kakao.getAuthorizationGrantType())
                .isEqualTo(
                    AuthorizationGrantType.AUTHORIZATION_CODE
                );
            assertThat(kakao.getRedirectUri())
                .isEqualTo(
                    "{baseUrl}/login/oauth2/code/{registrationId}"
                );
            assertThat(kakao.getScopes())
                .containsExactlyInAnyOrderElementsOf(
                    Set.of(
                        "profile_nickname",
                        "profile_image"
                    )
                );

            assertThat(
                kakao.getProviderDetails()
                    .getAuthorizationUri()
            ).isEqualTo(
                "https://kauth.kakao.com/oauth/authorize"
            );

            assertThat(
                kakao.getProviderDetails()
                    .getTokenUri()
            ).isEqualTo(
                "https://kauth.kakao.com/oauth/token"
            );

            assertThat(
                kakao.getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUri()
            ).isEqualTo(
                "https://kapi.kakao.com/v2/user/me"
            );

            /*
             * Kakao 사용자 정보 응답의 최상위 id는 이메일과 달리
             * Provider 내부에서 사용자를 안정적으로 식별하는 회원번호
             */
            assertThat(
                kakao.getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUserNameAttributeName()
            ).isEqualTo("id");
        });
    }
}
