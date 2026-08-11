package com.mopl.user.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 발급에 필요한 설정값을 관리하는 클래스
 *
 * application.yml의 refresh-token 설정을 자바 객체로 바인딩
 * Refresh Token 만료 시간을 Service 코드에 직접 작성하지 않고
 * 환경 변수 또는 설정 파일을 통해 변경할 수 있도록 분리
 *
 * 예를 들어 application.yml에 다음과 같이 작성하면
 * expiration 필드에 Duration.ofDays(7)에 해당하는 값이 주입
 *
 * refresh-token:
 *   expiration: 7d
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "refresh-token")
public class RefreshTokenProperties {

    /**
     * Refresh Token이 유효한 전체 기간
     *
     * 기본 정책은 발급 시점부터 7일이며
     * 이후 Refresh Token 발급 Service에서 만료 시각을 계산할 때 사용
     */
    private Duration expiration;
}
