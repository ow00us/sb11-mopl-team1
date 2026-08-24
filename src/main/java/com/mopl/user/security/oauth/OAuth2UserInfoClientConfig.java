package com.mopl.user.security.oauth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestTemplate;

/**
 * OAuth Provider 사용자 정보 조회에 사용하는 HTTP 클라이언트 설정
 *
 * <p>Google OIDC와 Kakao OAuth2 사용자 정보 조회에 명시적인 연결 및
 * 응답 제한 시간을 적용합니다. Provider 응답이 지연되더라도 인증 요청
 * 스레드가 무기한 점유되지 않도록 합니다.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2UserInfoClientConfig {

    /**
     * Google OIDC 사용자 정보 조회 Delegate를 생성
     *
     * <p>OIDC ID Token 처리에는 {@link OidcUserService}를 사용하고,
     * 추가 UserInfo Endpoint 호출에는 타임아웃이 설정된
     * {@link DefaultOAuth2UserService}를 사용합니다.</p>
     *
     * @param connectTimeout Provider 연결 제한 시간
     * @param readTimeout Provider 응답 제한 시간
     * @return Google OIDC 사용자 정보 조회 Delegate
     */
    @Bean("googleOidcUserDelegate")
    public OAuth2UserService<OidcUserRequest, OidcUser>
    googleOidcUserDelegate(
        @Value("${app.oauth2.user-info.connect-timeout}")
        Duration connectTimeout,
        @Value("${app.oauth2.user-info.read-timeout}")
        Duration readTimeout
    ) {
        DefaultOAuth2UserService oauth2UserService =
            createDefaultOAuth2UserService(
                connectTimeout,
                readTimeout
            );

        OidcUserService oidcUserService = new OidcUserService();

        /*
         * OIDC ID Token 검증은 OidcUserService가 담당하고,
         * UserInfo Endpoint 호출만 타임아웃이 설정된
         * DefaultOAuth2UserService에 위임한다.
         */
        oidcUserService.setOauth2UserService(oauth2UserService);

        return oidcUserService;
    }

    /**
     * Kakao OAuth2 사용자 정보 조회 Delegate를 생성
     *
     * <p>Kakao는 일반 OAuth2 Provider이므로
     * {@link DefaultOAuth2UserService}를 직접 사용합니다.</p>
     *
     * @param connectTimeout Provider 연결 제한 시간
     * @param readTimeout Provider 응답 제한 시간
     * @return Kakao OAuth2 사용자 정보 조회 Delegate
     */
    @Bean("kakaoOAuth2UserDelegate")
    public OAuth2UserService<OAuth2UserRequest, OAuth2User>
    kakaoOAuth2UserDelegate(
        @Value("${app.oauth2.user-info.connect-timeout}")
        Duration connectTimeout,
        @Value("${app.oauth2.user-info.read-timeout}")
        Duration readTimeout
    ) {
        return createDefaultOAuth2UserService(
            connectTimeout,
            readTimeout
        );
    }

    /**
     * 타임아웃이 설정된 공통 OAuth2 사용자 정보 조회 Service를 생성
     *
     * @param connectTimeout Provider 연결 제한 시간
     * @param readTimeout Provider 응답 제한 시간
     * @return 타임아웃이 적용된 DefaultOAuth2UserService
     */
    private DefaultOAuth2UserService createDefaultOAuth2UserService(
        Duration connectTimeout,
        Duration readTimeout
    ) {
        int connectTimeoutMillis = toTimeoutMillis(
            connectTimeout,
            "app.oauth2.user-info.connect-timeout"
        );
        int readTimeoutMillis = toTimeoutMillis(
            readTimeout,
            "app.oauth2.user-info.read-timeout"
        );

        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(connectTimeoutMillis);
        requestFactory.setReadTimeout(readTimeoutMillis);

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        /*
         * OAuth Provider가 반환한 OAuth2 오류 응답을
         * Spring Security의 OAuth2AuthenticationException으로 변환
         */
        restTemplate.setErrorHandler(
            new OAuth2ErrorResponseErrorHandler()
        );

        DefaultOAuth2UserService oauth2UserService =
            new DefaultOAuth2UserService();

        oauth2UserService.setRestOperations(restTemplate);

        return oauth2UserService;
    }

    /**
     * Duration 설정값을 HTTP 클라이언트가 사용하는 밀리초 단위 int로 변환
     *
     * @param timeout 변환할 제한 시간
     * @param settingName 오류 메시지에 사용할 설정 이름
     * @return 밀리초 단위 제한 시간
     */
    private int toTimeoutMillis(
        Duration timeout,
        String settingName
    ) {
        if (
            timeout == null
                || timeout.isZero()
                || timeout.isNegative()
        ) {
            throw new IllegalArgumentException(
                settingName + "은 0보다 커야 합니다."
            );
        }

        try {
            long timeoutMillis = timeout.toMillis();

            if (timeoutMillis < 1) {
                throw new IllegalArgumentException(
                    settingName + "은 최소 1ms 이상이어야 합니다."
                );
            }

            return Math.toIntExact(timeoutMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                settingName + "이 지원 범위를 초과했습니다.",
                exception
            );
        }
    }
}
