package com.mopl.user.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 계정 연결 과정에서 사용하는 임시 상태 설정
 *
 * <p>로그인된 사용자가 OAuth 계정 연결을 시작한 뒤 Provider 인증을
 * 완료할 때까지 연결 의도를 임시로 유지합니다.</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "app.oauth2.link"
)
public class OAuthLinkProperties {

    /**
     * OAuth 계정 연결 의도의 최대 유효 시간
     *
     * <p>사용자가 Provider 인증을 완료하지 않으면 이 시간이 지난 뒤
     * 연결 요청을 더 이상 사용할 수 없습니다.</p>
     */
    @NotNull(
        message =
            "OAuth 계정 연결 의도 만료 시간은 반드시 설정해야 합니다."
    )
    private Duration intentExpiration;

    /**
     * 만료 시간이 최소 1초 이상의 정수 초인지 검증
     *
     * @return 유효한 만료 시간이거나 null이면 true
     */
    @AssertTrue(
        message =
            "OAuth 계정 연결 의도 만료 시간은 1초 이상의 정수 초여야 합니다."
    )
    public boolean isIntentExpirationValid() {
        return intentExpiration == null
            || (
            intentExpiration.compareTo(
                Duration.ofSeconds(1)
            ) >= 0
                && intentExpiration.getNano() == 0
        );
    }
}
