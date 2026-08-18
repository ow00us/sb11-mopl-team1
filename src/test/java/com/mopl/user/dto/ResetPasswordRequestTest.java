package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ResetPasswordRequest에 선언된 이메일 입력 정책을 검증하는 단위 테스트
 *
 * <p>Spring MVC와 데이터베이스를 실행하지 않고 Bean Validation 규칙과
 * DTO 생성자의 공백 제거 동작만 빠르게 확인합니다.</p>
 */
class ResetPasswordRequestTest {

    /**
     * DTO의 @NotBlank, @Email, @Size 제약 조건을 실행할 Validator
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Test
    @DisplayName("올바른 이메일은 허용한다")
    void validate_success_whenEmailIsValid() {
        // given
        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "user@example.com"
            );

        // when
        Set<ConstraintViolation<ResetPasswordRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("이메일의 앞뒤 공백을 제거한다")
    void constructor_stripsEmailWhitespace() {
        // when
        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "  User@Example.com  "
            );

        // then
        /*
         * DTO는 앞뒤 공백만 제거
         * 소문자 변환은 사용자 조회 정책을 담당하는 Service에서 수행
         */
        assertThat(request.email())
            .isEqualTo("User@Example.com");

        assertThat(
            validator.validate(request)
        ).isEmpty();
    }

    @Test
    @DisplayName("이메일이 비어 있으면 검증에 실패한다")
    void validate_fail_whenEmailIsBlank() {
        // given
        ResetPasswordRequest request =
            new ResetPasswordRequest("   ");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains("이메일을 입력해주세요.");
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 검증에 실패한다")
    void validate_fail_whenEmailFormatIsInvalid() {
        // given
        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "invalid-email"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "이메일 형식이 올바르지 않습니다."
            );
    }

    @Test
    @DisplayName("이메일이 100자를 초과하면 검증에 실패한다")
    void validate_fail_whenEmailLengthExceeds100() {
        // given
        /*
         * 도메인 89자 + @example.com 12자로 총 101자의 이메일을 만든다.
         */
        String email =
            "a".repeat(89)
                + "@example.com";

        ResetPasswordRequest request =
            new ResetPasswordRequest(email);

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(email).hasSize(101);

        assertThat(messages)
            .contains(
                "이메일은 100자 이하로 작성 가능합니다."
            );
    }

    @Test
    @DisplayName("null 이메일은 생성 과정에서 예외 없이 검증 실패로 처리한다")
    void validate_fail_whenEmailIsNull() {
        // given
        ResetPasswordRequest request =
            new ResetPasswordRequest(null);

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        /*
         * Compact Constructor가 null에 strip()을 호출하지 않아야 하고,
         * null 여부는 @NotBlank가 검증해야 한다.
         */
        assertThat(messages)
            .contains("이메일을 입력해주세요.");
    }

    /**
     * Bean Validation 결과에서 검증 실패 메시지만 추출
     *
     * <p>한 입력이 여러 제약 조건에 동시에 실패할 수 있으므로
     * 전체 위반 개수가 아니라 필요한 메시지의 포함 여부를 확인합니다.</p>
     *
     * @param request 검증할 비밀번호 초기화 요청
     * @return 검증 실패 메시지 집합
     */
    private Set<String> validationMessages(
        ResetPasswordRequest request
    ) {
        return validator.validate(request)
            .stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.toSet());
    }
}
