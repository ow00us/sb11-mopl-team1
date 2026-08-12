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
     * Refresh Token 만료 시간이 0보다 큰지 검증
     *
     * <p>0 또는 음수로 설정되면 Redis 세션이 즉시 만료되거나
     * Cookie의 Max-Age가 유효하지 않게 됩니다. 따라서 잘못된 설정으로
     * 애플리케이션이 실행되지 않도록 시작 시점에 차단합니다.</p>
     *
     * @return 만료 시간이 null이거나 0보다 크면 true
     */
    @AssertTrue(
        message = "Refresh Token 만료 시간은 0보다 커야 합니다."
    )
    public boolean isExpirationPositive() {
        /*
         * null은 @NotNull이 별도로 처리합니다.
         * null에서 true를 반환하여 같은 설정 오류에 대해
         * 검증 메시지가 중복으로 생성되지 않도록 합니다.
         */
        return expiration == null
            || (!expiration.isZero()
            && !expiration.isNegative());
    }
}
