package com.mopl.user.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
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
@Validated
@ConfigurationProperties(prefix = "refresh-token")
public class RefreshTokenProperties {

    /**
     * Refresh Token이 유효한 전체 기간
     *
     * 기본 정책은 발급 시점부터 7일이며
     * 이후 Refresh Token 발급 Service에서 만료 시각을 계산할 때 사용
     */
    @NotNull(message = "Refresh Token 만료 시간은 반드시 설정해야 합니다.")
    private Duration expiration;

    /**
     * Refresh Token 만료 시간이 Cookie와 Redis에서
     * 동일하게 표현될 수 있는 유효한 값인지 검증
     *
     * <p>Redis TTL은 밀리초 단위를 지원하지만 Cookie의 Max-Age는
     * 초 단위로 표현됩니다. 따라서 1초 미만이거나 소수 초가 포함된
     * Duration을 허용하면 Redis 세션과 Cookie의 만료 시간이 달라집니다.</p>
     *
     * <p>만료 시간은 최소 1초 이상이며 나노초 부분이 없는
     * 양의 정수 초로 제한합니다.</p>
     *
     * @return null이거나 1초 이상의 양의 정수 초이면 true
     */
    @AssertTrue(
        message = "Refresh Token 만료 시간은 1초 이상의 정수 초여야 합니다."
    )
    public boolean isExpirationValid() {
        /*
         * null은 @NotNull이 별도로 처리
         *
         * compareTo(Duration.ofSeconds(1)) >= 0:
         * 최소 1초 이상인지 검사
         *
         * getNano() == 0:
         * 1.5초와 같이 소수 초가 포함되지 않았는지 검사
         */
        return expiration == null
            || (
            expiration.compareTo(Duration.ofSeconds(1)) >= 0
                && expiration.getNano() == 0
        );
    }
}
