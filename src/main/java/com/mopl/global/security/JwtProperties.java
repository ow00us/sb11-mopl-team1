package com.mopl.global.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml의 JWT 설정값을 바인딩하고 검증하는 클래스
 *
 * <p>JWT 발급과 검증에 필요한 issuer, Secret, Access Token 만료 시간은
 * 코드에 직접 작성하지 않고 환경 변수 또는 배포 설정으로 주입합니다.</p>
 *
 * <p>{@link Validated}를 적용하여 잘못된 JWT 설정이 실제 토큰 발급
 * 요청이 들어온 이후가 아니라 애플리케이션 시작 시점에 발견되도록
 * 합니다.</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * 토큰 발급자를 식별하는 값
     *
     * <p>토큰 발급 시 iss 클레임에 저장되고,
     * 토큰 검증 시 동일한 발급자가 만든 토큰인지 확인하는 데 사용합니다.</p>
     */
    @NotBlank(
        message =
            "JWT issuer는 비어 있을 수 없습니다."
    )
    private String issuer;

    /**
     * HMAC SHA-256 서명에 사용할 Base64 형식의 비밀키
     *
     * <p>Secret 원문은 환경 변수로만 주입하며 코드, 로그 또는
     * 예외 메시지에 출력하지 않습니다.</p>
     */
    @NotBlank(
        message =
            "JWT Secret은 비어 있을 수 없습니다."
    )
    private String secret;

    /**
     * Access Token이 유효한 시간
     */
    @NotNull(
        message =
            "JWT Access Token 만료 시간은 필수입니다."
    )
    private Duration accessTokenExpiration;

    /**
     * issuer 앞뒤에 불필요한 공백이 없는지 검증
     *
     * <p>issuer는 JWT 발급과 검증에서 문자열이 정확히 일치해야 하므로
     * 눈에 보이지 않는 앞뒤 공백을 허용하지 않습니다.</p>
     *
     * <p>null과 빈 문자열은 {@link NotBlank}가 별도로 검증하므로
     * 여기서는 중복 검증 메시지가 발생하지 않도록 true를 반환합니다.</p>
     *
     * @return issuer 형식이 올바르면 true
     */
    @AssertTrue(
        message =
            "JWT issuer 앞뒤에는 공백을 사용할 수 없습니다."
    )
    public boolean isIssuerTrimmed() {
        if (issuer == null || issuer.isBlank()) {
            return true;
        }

        return issuer.equals(
            issuer.strip()
        );
    }

    /**
     * JWT Secret이 Base64 형식이며 HS256에 필요한 길이인지 검증
     *
     * <p>HS256 서명 키는 최소 256비트, 즉 32바이트 이상이어야 합니다.
     * 환경 변수에는 키 원문이 아니라 Base64로 인코딩한 문자열을
     * 주입하므로 먼저 Base64 디코딩 가능 여부를 확인합니다.</p>
     *
     * <p>Secret 값이나 디코딩 결과는 오류 메시지에 포함하지 않습니다.</p>
     *
     * @return 유효한 Base64이며 디코딩 결과가 32바이트 이상이면 true
     */
    @AssertTrue(
        message =
            "JWT Secret은 유효한 Base64이며 디코딩 결과가 32바이트 이상이어야 합니다."
    )
    public boolean isSecretValid() {
        /*
         * null과 빈 문자열은 @NotBlank가 처리
         * 같은 잘못된 값에 여러 검증 메시지가 생성되지 않도록
         * 여기서는 추가 검증을 생략
         */
        if (secret == null || secret.isBlank()) {
            return true;
        }

        try {
            byte[] decodedSecret =
                Base64.getDecoder()
                    .decode(secret);

            return decodedSecret.length >= 32;
        } catch (IllegalArgumentException exception) {
            /*
             * Base64 형식이 아니면 decode()가
             * IllegalArgumentException을 발생
             *
             * 검증 단계에서는 예외를 밖으로 전달하지 않고 false를
             * 반환하여 Configuration Properties 검증 오류로 처리
             */
            return false;
        }
    }

    /**
     * Access Token 만료 시간이 JWT에 사용할 수 있는 값인지 검증
     *
     * <p>최소 1초 이상이어야 하며, JWT NumericDate와 설정값의
     * 정밀도 차이를 방지하기 위해 정수 초 단위만 허용합니다.</p>
     *
     * <p>null은 {@link NotNull}이 별도로 검증합니다.</p>
     *
     * @return 1초 이상의 정수 초 Duration이면 true
     */
    @AssertTrue(
        message =
            "JWT Access Token 만료 시간은 1초 이상의 정수 초 단위여야 합니다."
    )
    public boolean isAccessTokenExpirationValid() {
        if (accessTokenExpiration == null) {
            return true;
        }

        /*
         * getSeconds()가 1 이상이면 0이나 음수 및 1초 미만을 거부
         * getNano()가 0이어야 1.5초 같은 소수 초 설정을 거부할 수 있다.
         */
        return accessTokenExpiration.getSeconds() >= 1
            && accessTokenExpiration.getNano() == 0;
    }
}
