package com.mopl.global.security;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yml의 jwt 설정값을 받는 클래스
 *
 * 비밀키와 토큰 만료 시간은 코드에 작성하지 않고
 * 환경 변수 또는 배포 환경의 설정으로 주입
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 토큰 발급 서비스를 식별하는 값
     */
    private String issuer;

    /**
     * HMAC 서명에 사용할 Base64 형식의 비밀키
     */
    private String secret;

    /**
     * 액세스 토큰이 유효한 시간
     */
    private Duration accessTokenExpiration;
}
