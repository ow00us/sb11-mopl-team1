package com.mopl.global.security;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.controller.CsrfTokenController;
import com.mopl.user.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import com.mopl.user.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import com.mopl.user.security.oauth.GoogleOidcUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SecurityPolicyProbeController.class)
@ActiveProfiles({
    "test",
    "security-policy-test"
})
@Import({
    SecurityConfig.class,
    CsrfTokenController.class
})
class OAuth2SecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @MockitoBean
    ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean
    OAuth2AuthenticationSuccessHandler successHandler;

    @MockitoBean
    OAuth2AuthenticationFailureHandler failureHandler;

    @MockitoBean
    GoogleOidcUserService googleOidcUserService;

    @BeforeEach
    void setUp() {
        when(
            clientRegistrationRepository
                .findByRegistrationId("google")
        ).thenReturn(
            googleClientRegistration()
        );
    }

    @Test
    @DisplayName("OAuth ClientRegistration이 있으면 OAuth 인증 시작 경로가 Provider로 이동한다")
    void authorizationEndpoint_redirectsToProvider()
        throws Exception {
        mockMvc.perform(
                get("/oauth2/authorization/google")
            )
            .andExpect(
                status().is3xxRedirection()
            )
            .andExpect(
                header().string(
                    HttpHeaders.LOCATION,
                    startsWith(
                        "https://accounts.example.com/oauth2/authorize"
                    )
                )
            );
    }

    @Test
    @DisplayName("OAuth Callback 실패는 등록한 실패 Handler로 전달된다")
    void callbackFailure_usesConfiguredFailureHandler()
        throws Exception {
        mockMvc.perform(
                get("/login/oauth2/code/google")
                    .param(
                        "error",
                        "access_denied"
                    )
                    .param(
                        "error_description",
                        "The user denied access"
                    )
            )
            .andExpect(
                status().isOk()
            );

        /*
         * 실패 Handler는 Mockito Bean이므로 실제 Redirect 응답을 만들지 않아
         * 응답 상태는 200으로 남는다.
         *
         * 여기서는 응답 상태 자체가 아니라 SecurityConfig에 등록한
         * 실패 Handler가 실제 OAuth2 Filter에서 호출되는지 검증
         */
        verify(failureHandler)
            .onAuthenticationFailure(
                any(),
                any(),
                any()
            );
    }

    /**
     * 외부 네트워크 요청 없이 OAuth2 FilterChain 구성을 검증하기 위한
     * 테스트 전용 Google ClientRegistration
     */
    private ClientRegistration googleClientRegistration() {
        return ClientRegistration
            .withRegistrationId("google")
            .clientId("test-google-client-id")
            .clientSecret("test-google-client-secret")
            .clientAuthenticationMethod(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
            )
            .authorizationGrantType(
                AuthorizationGrantType.AUTHORIZATION_CODE
            )
            .redirectUri(
                "{baseUrl}/login/oauth2/code/{registrationId}"
            )
            .scope(
                "openid",
                "profile",
                "email"
            )
            .authorizationUri(
                "https://accounts.example.com/oauth2/authorize"
            )
            .tokenUri(
                "https://accounts.example.com/oauth2/token"
            )
            .userInfoUri(
                "https://accounts.example.com/oauth2/userinfo"
            )
            .userNameAttributeName("sub")
            .clientName("Google")
            .build();
    }
}
