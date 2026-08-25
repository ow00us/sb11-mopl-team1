package com.mopl.user.security.oauth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** 인가 요청 저장소를 구성합니다. */
@Configuration
public class OAuth2AuthorizationRequestConfig {

    @Bean
    public AuthorizationRequestRepository<OAuth2AuthorizationRequest>
        authorizationRequestRepository(
            OAuth2AuthorizationRequestStore store,
            @Value("${mopl.oauth2.authorization-request.time-to-live}") Duration timeToLive
        ) {
        return new RedisOAuth2AuthorizationRequestRepository(store, timeToLive);
    }
}
