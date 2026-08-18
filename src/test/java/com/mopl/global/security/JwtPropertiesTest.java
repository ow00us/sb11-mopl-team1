package com.mopl.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급·검증 설정값의 Bean Validation 정책을 검증
 *
 * <p>잘못된 issuer, Secret, Access Token 만료 시간이
 * 실제 JWT 발급 과정까지 전달되지 않고 애플리케이션 시작 시점의
 * ConfigurationProperties 검증 과정에서 차단될 수 있는지 확인합니다.</p>
 */
class JwtPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /**
     * 테스트에서 JwtProperties의 Bean Validation을 실행할
     * Validator를 한 번 생성
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 모든 테스트가 끝난 뒤 ValidatorFactory가 사용하는
     * 내부 자원을 정리
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("JWT ConfigurationProperties에 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        // when
        boolean validatedAnnotationPresent =
            JwtProperties.class
                .isAnnotationPresent(Validated.class);

        // then
        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("유효한 JWT 설정값은 검증에 성공한다")
    void validate_success_whenAllPropertiesAreValid() {
        // given
        JwtProperties properties =
            createValidProperties();

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("JWT issuer가 null이면 검증에 실패한다")
    void validate_fail_whenIssuerIsNull() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setIssuer(null);

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT issuer는 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("JWT issuer가 공백으로만 구성되면 검증에 실패한다")
    void validate_fail_whenIssuerIsBlank() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setIssuer("   ");

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT issuer는 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("JWT issuer 앞뒤에 공백이 있으면 검증에 실패한다")
    void validate_fail_whenIssuerHasSurroundingWhitespace() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setIssuer(" mopl ");

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT issuer 앞뒤에는 공백을 사용할 수 없습니다."
            );
    }

    @Test
    @DisplayName("JWT Secret이 null이면 검증에 실패한다")
    void validate_fail_whenSecretIsNull() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setSecret(null);

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Secret은 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("JWT Secret이 공백으로만 구성되면 검증에 실패한다")
    void validate_fail_whenSecretIsBlank() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setSecret("   ");

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Secret은 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("JWT Secret이 Base64 형식이 아니면 검증에 실패한다")
    void validate_fail_whenSecretIsNotBase64() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setSecret("not-a-valid-base64-secret%%%");

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Secret은 유효한 Base64이며 디코딩 결과가 32바이트 이상이어야 합니다."
            );
    }

    @Test
    @DisplayName("JWT Secret의 디코딩 결과가 32바이트 미만이면 검증에 실패한다")
    void validate_fail_whenDecodedSecretIsShorterThan32Bytes() {
        // given
        JwtProperties properties =
            createValidProperties();

        /*
         * HS256은 최소 256비트, 즉 32바이트 길이의 키가 필요
         * 경계값 바로 아래인 31바이트를 사용해 거부되는지 확인
         */
        properties.setSecret(
            encodeSecretWithLength(31)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Secret은 유효한 Base64이며 디코딩 결과가 32바이트 이상이어야 합니다."
            );
    }

    @Test
    @DisplayName("JWT Secret의 디코딩 결과가 정확히 32바이트이면 허용한다")
    void validate_success_whenDecodedSecretIsExactly32Bytes() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setSecret(
            encodeSecretWithLength(32)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Access Token 만료 시간이 null이면 검증에 실패한다")
    void validate_fail_whenAccessTokenExpirationIsNull() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setAccessTokenExpiration(null);

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Access Token 만료 시간은 필수입니다."
            );
    }

    @Test
    @DisplayName("Access Token 만료 시간이 0이면 검증에 실패한다")
    void validate_fail_whenAccessTokenExpirationIsZero() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setAccessTokenExpiration(
            Duration.ZERO
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Access Token 만료 시간은 1초 이상의 정수 초 단위여야 합니다."
            );
    }

    @Test
    @DisplayName("Access Token 만료 시간이 음수이면 검증에 실패한다")
    void validate_fail_whenAccessTokenExpirationIsNegative() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setAccessTokenExpiration(
            Duration.ofSeconds(-1)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Access Token 만료 시간은 1초 이상의 정수 초 단위여야 합니다."
            );
    }

    @Test
    @DisplayName("Access Token 만료 시간이 1초 미만이면 검증에 실패한다")
    void validate_fail_whenAccessTokenExpirationIsLessThanOneSecond() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setAccessTokenExpiration(
            Duration.ofMillis(500)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Access Token 만료 시간은 1초 이상의 정수 초 단위여야 합니다."
            );
    }

    @Test
    @DisplayName("Access Token 만료 시간에 소수 초가 포함되면 검증에 실패한다")
    void validate_fail_whenAccessTokenExpirationContainsFractionalSecond() {
        // given
        JwtProperties properties =
            createValidProperties();

        /*
         * JWT의 NumericDate는 초 단위로 처리되므로 1.5초처럼
         * 소수 초가 포함된 설정은 실제 만료 시각과 설정값 사이에
         * 정밀도 차이를 만들 수 있어 허용하지 않는다.
         */
        properties.setAccessTokenExpiration(
            Duration.ofMillis(1500)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "JWT Access Token 만료 시간은 1초 이상의 정수 초 단위여야 합니다."
            );
    }

    @Test
    @DisplayName("Access Token 만료 시간이 정확히 1초이면 허용한다")
    void validate_success_whenAccessTokenExpirationIsExactlyOneSecond() {
        // given
        JwtProperties properties =
            createValidProperties();

        properties.setAccessTokenExpiration(
            Duration.ofSeconds(1)
        );

        // when
        Set<ConstraintViolation<JwtProperties>> violations =
            validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    /**
     * 개별 설정값만 변경하여 검증할 수 있도록
     * 기본적으로 유효한 JwtProperties를 생성
     *
     * @return 모든 검증 조건을 만족하는 JWT 설정 객체
     */
    private JwtProperties createValidProperties() {
        JwtProperties properties =
            new JwtProperties();

        properties.setIssuer("mopl");
        properties.setSecret(
            encodeSecretWithLength(32)
        );
        properties.setAccessTokenExpiration(
            Duration.ofMinutes(30)
        );

        return properties;
    }

    /**
     * 지정한 바이트 길이의 값을 Base64 문자열로 변환
     *
     * <p>JWT Secret 정책은 Base64 문자열의 글자 수가 아니라
     * 디코딩 이후의 실제 바이트 길이를 기준으로 판단해야 하므로
     * 테스트에서도 원본 바이트 배열을 먼저 만든 뒤 인코딩합니다.</p>
     *
     * @param byteLength Base64 인코딩 전 원본 바이트 길이
     * @return Base64로 인코딩된 Secret 문자열
     */
    private String encodeSecretWithLength(int byteLength) {
        byte[] secretBytes =
            new byte[byteLength];

        /*
         * 테스트용 값이며 실제 운영 Secret으로 사용되지 않는다.
         * 검증 대상은 무작위성이 아니라 디코딩 이후 길이
         */
        return Base64.getEncoder()
            .encodeToString(secretBytes);
    }
}
