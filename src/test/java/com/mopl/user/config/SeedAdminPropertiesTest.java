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
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * Seed 관리자 설정의 활성 프로파일과 Bean Validation 정책을 검증합니다.
 *
 * <p>Seed 관리자 정보는 실행 환경에서 주입되므로 잘못된 값이 전달되면
 * 계정 생성 시점까지 진행하지 않고 애플리케이션 설정 검증 단계에서
 * 발견할 수 있어야 합니다.</p>
 */
class SeedAdminPropertiesTest {

    /**
     * 테스트 클래스 전체에서 사용할 Bean Validator 생성 Factory
     */
    private static ValidatorFactory validatorFactory;

    /**
     * SeedAdminProperties에 선언된 Bean Validation 제약 조건을
     * 실제로 검사하는 Validator
     */
    private static Validator validator;

    /**
     * 모든 테스트 실행 전에 Validator를 한 번 생성합니다.
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 모든 테스트가 종료된 후 ValidatorFactory가 사용하는
     * 내부 리소스를 정리합니다.
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("ConfigurationProperties에 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        /*
         * 필드에 Bean Validation 어노테이션이 있어도 클래스에
         * @Validated가 없으면 설정 바인딩 시 자동 검증되지 않습니다.
         */
        boolean validatedAnnotationPresent =
            SeedAdminProperties.class
                .isAnnotationPresent(Validated.class);

        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("Seed 관리자 설정은 seed 프로파일에서만 등록된다")
    void propertiesIsRegisteredOnlyInSeedProfile() {
        /*
         * Seed 관리자 설정이 기본·운영 프로파일에 등록되면
         * 일반 애플리케이션 실행에서도 관리자 설정값을 요구할 수 있습니다.
         *
         * @Profile("seed") 선언이 유지되는지 직접 확인합니다.
         */
        Profile profile =
            SeedAdminProperties.class
                .getAnnotation(Profile.class);

        assertThat(profile)
            .isNotNull();

        assertThat(profile.value())
            .containsExactly("seed");
    }

    @Test
    @DisplayName("유효한 Seed 관리자 설정은 허용한다")
    void validate_success_whenPropertiesAreValid() {
        // given
        SeedAdminProperties properties =
            validProperties();

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Seed 관리자 이름이 null이면 검증에 실패한다")
    void validate_fail_whenNameIsNull() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setName(null);

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "name"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이름이 공백이면 검증에 실패한다")
    void validate_fail_whenNameIsBlank() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setName("   ");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "name"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이름이 30자를 초과하면 검증에 실패한다")
    void validate_fail_whenNameExceedsMaximumLength() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setName("가".repeat(31));

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "name"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이메일이 null이면 검증에 실패한다")
    void validate_fail_whenEmailIsNull() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setEmail(null);

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "email"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이메일이 공백이면 검증에 실패한다")
    void validate_fail_whenEmailIsBlank() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setEmail("   ");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "email"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이메일 형식이 올바르지 않으면 검증에 실패한다")
    void validate_fail_whenEmailFormatIsInvalid() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setEmail("invalid-email");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "email"
        );
    }

    @Test
    @DisplayName("Seed 관리자 이메일이 100자를 초과하면 검증에 실패한다")
    void validate_fail_whenEmailExceedsMaximumLength() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setEmail(
            "a".repeat(89) + "@example.com"
        );

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        /*
         * 앞부분 89자와 @example.com 12자를 합치면 총 101자입니다.
         */
        assertThat(properties.getEmail())
            .hasSize(101);

        assertHasViolationForProperty(
            violations,
            "email"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호가 null이면 검증에 실패한다")
    void validate_fail_whenPasswordIsNull() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword(null);

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호가 8자 미만이면 검증에 실패한다")
    void validate_fail_whenPasswordIsTooShort() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword("Abc12!");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호가 72자를 초과하면 검증에 실패한다")
    void validate_fail_whenPasswordIsTooLong() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword(
            "A1!" + "a".repeat(70)
        );

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        /*
         * 접두사 3자와 반복 문자열 70자를 합쳐 총 73자입니다.
         */
        assertThat(properties.getPassword())
            .hasSize(73);

        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호에 영문이 없으면 검증에 실패한다")
    void validate_fail_whenPasswordDoesNotContainLetter() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword("12345678!");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호에 숫자가 없으면 검증에 실패한다")
    void validate_fail_whenPasswordDoesNotContainDigit() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword("Password!");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호에 특수문자가 없으면 검증에 실패한다")
    void validate_fail_whenPasswordDoesNotContainSpecialCharacter() {
        // given
        SeedAdminProperties properties =
            validProperties();

        properties.setPassword("Password1");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    @Test
    @DisplayName("Seed 관리자 비밀번호에 허용하지 않는 문자가 있으면 검증에 실패한다")
    void validate_fail_whenPasswordContainsInvalidCharacter() {
        // given
        SeedAdminProperties properties =
            validProperties();

        /*
         * 현재 비밀번호 정책은 ASCII 영문, 숫자와 허용 특수문자만
         * 사용할 수 있으므로 한글이 포함된 값은 거부합니다.
         */
        properties.setPassword("Password1!한글");

        // when
        Set<ConstraintViolation<SeedAdminProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "password"
        );
    }

    /**
     * 각 테스트에서 일부 필드만 변경할 수 있도록
     * 기본적으로 유효한 Seed 관리자 설정을 생성합니다.
     *
     * @return 모든 Bean Validation 조건을 만족하는 설정
     */
    private SeedAdminProperties validProperties() {
        SeedAdminProperties properties =
            new SeedAdminProperties();

        properties.setName("시연 관리자");
        properties.setEmail("admin@example.com");
        properties.setPassword("SeedAdmin1!");

        return properties;
    }

    /**
     * 지정한 필드에 Bean Validation 위반이 발생했는지 확인합니다.
     *
     * <p>같은 필드에 @NotBlank와 @Pattern 등 여러 제약 조건이
     * 동시에 실패할 수 있으므로 위반 개수를 고정하지 않고,
     * 해당 필드의 위반이 하나 이상 존재하는지만 확인합니다.</p>
     *
     * @param violations Bean Validation 실행 결과
     * @param propertyName 위반이 발생해야 하는 필드 이름
     */
    private void assertHasViolationForProperty(
        Set<ConstraintViolation<SeedAdminProperties>> violations,
        String propertyName
    ) {
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains(propertyName);
    }
}
