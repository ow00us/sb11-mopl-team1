package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

/**
 * 비밀번호 초기화 이메일 설정값의 검증 정책을 확인
 *
 * <p>메일 관련 환경변수가 잘못 설정된 경우 실제 메일 발송 시점이 아니라
 * 애플리케이션 시작 시점에 발견할 수 있도록 선언된 Bean Validation
 * 제약 조건을 검증합니다.</p>
 */
class PasswordResetMailPropertiesTest {

    /**
     * Bean Validation 리소스를 생성하고 종료할 수 있는 팩토리
     */
    private static ValidatorFactory validatorFactory;

    /**
     * PasswordResetMailProperties에 선언된 제약 조건을 검사
     */
    private static Validator validator;

    /**
     * 테스트 클래스 실행 전에 Validator를 한 번 생성
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 전체 테스트가 끝나면 ValidatorFactory가 가진 리소스를 정리
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("ConfigurationProperties 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        boolean validatedAnnotationPresent =
            PasswordResetMailProperties.class
                .isAnnotationPresent(
                    Validated.class
                );

        assertThat(
            validatedAnnotationPresent
        ).isTrue();
    }

    @Test
    @DisplayName("올바른 발신 주소와 제목은 검증에 성공한다")
    void validate_success_whenPropertiesAreValid() {
        // given
        PasswordResetMailProperties properties =
            validProperties();

        // when
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("발신 주소가 공백이면 검증에 실패한다")
    void validate_fail_whenFromAddressIsBlank() {
        // given
        PasswordResetMailProperties properties =
            validProperties();

        properties.setFromAddress("   ");

        // when
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "fromAddress"
        );
    }

    @Test
    @DisplayName("발신 주소가 이메일 형식이 아니면 검증에 실패한다")
    void validate_fail_whenFromAddressIsInvalid() {
        // given
        PasswordResetMailProperties properties =
            validProperties();

        properties.setFromAddress(
            "invalid-email-address"
        );

        // when
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "fromAddress"
        );
    }

    @Test
    @DisplayName("메일 제목이 공백이면 검증에 실패한다")
    void validate_fail_whenSubjectIsBlank() {
        // given
        PasswordResetMailProperties properties =
            validProperties();

        properties.setSubject("   ");

        // when
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "subject"
        );
    }

    @Test
    @DisplayName("메일 제목에 줄바꿈 문자가 있으면 검증에 실패한다")
    void validate_fail_whenSubjectContainsNewLine() {
        // given
        PasswordResetMailProperties properties =
            validProperties();

        /*
         * 줄바꿈 이후에 임의의 메일 헤더를 삽입하려는 값을 가정합니다.
         */
        properties.setSubject(
            "[모두의 플리] 임시 비밀번호 안내\r\nBcc: attacker@example.com"
        );

        // when
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "subject"
        );
    }

    /**
     * 모든 필드가 유효한 기본 설정 객체를 생성
     *
     * @return 유효한 비밀번호 초기화 메일 설정
     */
    private PasswordResetMailProperties validProperties() {
        PasswordResetMailProperties properties =
            new PasswordResetMailProperties();

        properties.setFromAddress(
            "no-reply@mopl.local"
        );

        properties.setSubject(
            "[모두의 플리] 임시 비밀번호 안내"
        );

        return properties;
    }

    /**
     * 특정 필드에 대한 검증 오류가 존재하는지 확인
     *
     * @param violations Bean Validation 검증 결과
     * @param propertyName 오류가 발생해야 하는 필드 이름
     */
    private void assertHasViolationForProperty(
        Set<ConstraintViolation<PasswordResetMailProperties>>
            violations,
        String propertyName
    ) {
        assertThat(violations)
            .extracting(
                violation ->
                    violation
                        .getPropertyPath()
                        .toString()
            )
            .contains(propertyName);
    }
}
