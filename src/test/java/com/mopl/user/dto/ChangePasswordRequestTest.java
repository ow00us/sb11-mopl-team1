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
 * ChangePasswordRequest에 선언된 새 비밀번호 입력 정책을 검증
 *
 * Spring MVC나 데이터베이스를 실행하지 않고 Bean Validation 규칙만
 * 실행하므로 빠르게 확인할 수 있는 단위 테스트
 *
 * 비밀번호 변경에서도 회원가입과 동일한 비밀번호 정책을 사용해야 한다.
 */
class ChangePasswordRequestTest {

    /**
     * DTO에 선언된 @NotBlank, @Size, @Pattern을 실행하는 Validator입니다.
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("영문, 숫자, 특수문자를 포함한 비밀번호는 허용한다")
    void validate_success_whenPasswordIsValid() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        // when
        Set<ConstraintViolation<ChangePasswordRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("72자 비밀번호는 허용한다")
    void validate_success_whenPasswordLengthIs72() {
        // given
        /*
         * "Aa1!"은 영문, 숫자, 특수문자를 모두 포함한 4자
         * 여기에 영문 68자를 추가하여 총 72자를 만든다.
         */
        String password =
            "Aa1!" + "a".repeat(68);

        ChangePasswordRequest request =
            new ChangePasswordRequest(password);

        // when
        Set<ConstraintViolation<ChangePasswordRequest>> violations =
            validator.validate(request);

        // then
        assertThat(password).hasSize(72);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("비밀번호가 비어 있으면 검증에 실패한다")
    void validate_fail_whenPasswordIsBlank() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest(" ");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        /*
         * 빈 값은 @NotBlank뿐만 아니라 @Size와 @Pattern에도 동시에
         * 실패할 수 있으므로 전체 위반 개수를 단정하지 않고
         * 필수 메시지가 포함되는지만 확인
         */
        assertThat(messages)
            .contains("비밀번호를 입력해주세요.");
    }

    @Test
    @DisplayName("비밀번호가 8자보다 짧으면 검증에 실패한다")
    void validate_fail_whenPasswordIsTooShort() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest("Aa1!");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains("비밀번호는 8~72자로 작성 가능합니다.");
    }

    @Test
    @DisplayName("비밀번호가 72자를 초과하면 검증에 실패한다")
    void validate_fail_whenPasswordLengthExceeds72() {
        // given
        String password =
            "Aa1!" + "a".repeat(69);

        ChangePasswordRequest request =
            new ChangePasswordRequest(password);

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(password).hasSize(73);
        assertThat(messages)
            .contains("비밀번호는 8~72자로 작성 가능합니다.");
    }

    @Test
    @DisplayName("특수문자가 없으면 검증에 실패한다")
    void validate_fail_whenSpecialCharacterDoesNotExist() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    @Test
    @DisplayName("숫자가 없으면 검증에 실패한다")
    void validate_fail_whenNumberDoesNotExist() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword!");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    @Test
    @DisplayName("한글이 포함되면 검증에 실패한다")
    void validate_fail_whenPasswordContainsKorean() {
        // given
        ChangePasswordRequest request =
            new ChangePasswordRequest("새비밀번호1!");

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    /**
     * DTO 검증 결과에서 오류 메시지만 추출
     *
     * 하나의 값이 여러 검증 규칙에 동시에 실패할 수 있으므로
     * 테스트에서는 필요한 오류 메시지의 포함 여부를 확인
     *
     * @param request 검증할 비밀번호 변경 요청
     * @return 검증 실패 메시지 집합
     */
    private Set<String> validationMessages(
        ChangePasswordRequest request
    ) {
        return validator.validate(request)
            .stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.toSet());
    }
}
