package com.mopl.user.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 전용 사용자가 로컬 이메일·비밀번호 로그인 수단을
 * 추가할 때 사용하는 이메일 인증 정책
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "app.oauth2.local-credential"
)
public class OAuthLocalCredentialProperties {

    /**
     * 이메일 인증 코드의 유효 시간
     */
    @NotNull(
        message = "OAuth 로컬 로그인 인증 코드 만료 시간은 반드시 설정해야 합니다."
    )
    private Duration verificationExpiration;

    /**
     * 같은 사용자가 인증 메일을 다시 요청할 수 있기까지의 대기 시간
     */
    @NotNull(
        message = "OAuth 로컬 로그인 인증 메일 재전송 대기 시간은 반드시 설정해야 합니다."
    )
    private Duration resendCooldown;

    /**
     * 인증 코드가 폐기되기 전까지 허용할 최대 검증 실패 횟수
     */
    @NotNull(
        message = "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 반드시 설정해야 합니다."
    )
    @Min(
        value = 1,
        message = "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 1 이상이어야 합니다."
    )
    @Max(
        value = 10,
        message = "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 10 이하여야 합니다."
    )
    private Integer maxAttempts;

    /**
     * 이메일 인증 코드 HMAC 생성에 사용하는 서버 비밀키
     *
     * <p>6자리 인증 코드는 경우의 수가 작으므로 단순 SHA-256만 사용하면
     * Redis 데이터가 노출됐을 때 전체 코드 대입이 가능합니다.
     * 서버 비밀키를 포함한 HMAC-SHA256을 사용해 이를 방지합니다.</p>
     */
    @NotBlank(
        message = "OAuth 로컬 로그인 인증 코드 비밀키는 반드시 설정해야 합니다."
    )
    @Size(
        min = 32,
        max = 512,
        message = "OAuth 로컬 로그인 인증 코드 비밀키는 32~512자여야 합니다."
    )
    private String verificationSecret;

    /**
     * Redis TTL에 사용할 두 시간이 양의 정수 초인지 확인하고,
     * 재전송 대기 시간이 인증 코드 수명보다 짧은지 검증
     *
     * @return 설정이 유효하거나 필수 값 검증에 맡길 null 값이면 true
     */
    @AssertTrue(
        message = "OAuth 로컬 로그인 인증 시간 설정은 1초 이상의 정수 초이며 재전송 대기 시간이 인증 코드 만료 시간보다 짧아야 합니다."
    )
    public boolean isDurationPolicyValid() {
        if (
            verificationExpiration == null
                || resendCooldown == null
        ) {
            return true;
        }

        boolean expirationValid =
            isPositiveWholeSecond(verificationExpiration);

        boolean cooldownValid =
            isPositiveWholeSecond(resendCooldown);

        return expirationValid
            && cooldownValid
            && resendCooldown.compareTo(
            verificationExpiration
        ) < 0;
    }

    private boolean isPositiveWholeSecond(
        Duration duration
    ) {
        return duration.compareTo(
            Duration.ofSeconds(1)
        ) >= 0
            && duration.getNano() == 0;
    }
}
