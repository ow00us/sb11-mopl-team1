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
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.client.RestTemplate;

/**
 * OAuth Provider의 UserInfo Endpoint를 호출하는 HTTP Client 설정
 *
 * <p>외부 OAuth Provider가 지연되거나 응답하지 않을 때 로그인 요청
 * 스레드가 장시간 점유되지 않도록 연결 및 응답 제한 시간을 적용합니다.</p>
 */
@Configuration(proxyBeanMethods = false)
public class OAuth2UserInfoClientConfig {

    /**
     * Google OIDC UserInfo 요청에 제한 시간이 적용된 delegate를 생성
     *
     * <p>OidcUserService의 ID Token 처리는 그대로 사용하고, 추가 UserInfo
     * 요청만 제한 시간이 설정된 DefaultOAuth2UserService로 교체합니다.</p>
     */
    @Bean("googleOidcUserDelegate")
    public OAuth2UserService<OidcUserRequest, OidcUser>
    googleOidcUserDelegate(
        @Value(
            "${app.oauth2.user-info.connect-timeout}"
        )
        Duration connectTimeout,
        @Value(
            "${app.oauth2.user-info.read-timeout}"
        )
        Duration readTimeout
    ) {
        int connectTimeoutMillis =
            toTimeoutMillis(
                connectTimeout,
                "OAuth UserInfo 연결 제한 시간"
            );

        int readTimeoutMillis =
            toTimeoutMillis(
                readTimeout,
                "OAuth UserInfo 응답 제한 시간"
            );

        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(
            connectTimeoutMillis
        );
        requestFactory.setReadTimeout(
            readTimeoutMillis
        );

        RestTemplate restTemplate =
            new RestTemplate(requestFactory);

        /*
         * Spring Security OAuth 오류 응답을 OAuth2AuthenticationException으로
         * 변환하는 기존 표준 오류 처리기를 유지
         */
        restTemplate.setErrorHandler(
            new OAuth2ErrorResponseErrorHandler()
        );

        DefaultOAuth2UserService oauth2UserService =
            new DefaultOAuth2UserService();

        oauth2UserService.setRestOperations(
            restTemplate
        );

        OidcUserService oidcUserService =
            new OidcUserService();

        oidcUserService.setOauth2UserService(
            oauth2UserService
        );

        return oidcUserService;
    }

    private int toTimeoutMillis(
        Duration timeout,
        String settingName
    ) {
        if (timeout == null
            || timeout.isZero()
            || timeout.isNegative()) {
            throw new IllegalArgumentException(
                settingName
                    + "은 0보다 커야 합니다."
            );
        }

        try {
            long timeoutMillis = timeout.toMillis();

            if (timeoutMillis < 1) {
                throw new IllegalArgumentException(
                    settingName
                        + "은 최소 1ms 이상이어야 합니다."
                );
            }

            return Math.toIntExact(timeoutMillis);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                settingName
                    + "이 지원 범위를 초과했습니다.",
                exception
            );
        }
    }
}
