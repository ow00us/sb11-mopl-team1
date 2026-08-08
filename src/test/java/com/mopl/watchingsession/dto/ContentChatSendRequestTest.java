package com.mopl.watchingsession.dto;

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

public class ContentChatSendRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("content가 공백이면 검증 실패")
    void validate_blankContent_fails() {
        ContentChatSendRequest request = new ContentChatSendRequest("   ");

        Set<ConstraintViolation<ContentChatSendRequest>> violations = validator.validate(request);

        assertThat(violations)
            .isNotEmpty()
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("content");
    }

    @Test
    @DisplayName("content가 null이면 검증 실패")
    void validate_nullContent_fails() {
        ContentChatSendRequest request = new ContentChatSendRequest(null);

        Set<ConstraintViolation<ContentChatSendRequest>> violations = validator.validate(request);

        assertThat(violations)
            .isNotEmpty()
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("content");
    }

    @Test
    @DisplayName("content가 500자를 초과하면 검증 실패")
    void validate_contentOver500Chars_fails() {
        String tooLong = "가".repeat(501);
        ContentChatSendRequest request = new ContentChatSendRequest(tooLong);

        Set<ConstraintViolation<ContentChatSendRequest>> violations = validator.validate(request);

        assertThat(violations)
            .isNotEmpty()
            .extracting(v -> v.getPropertyPath().toString())
            .containsExactly("content");
    }

    @Test
    @DisplayName("content가 정확히 500자면 검증 통과")
    void validate_contentExactly500Chars_passes() {
        String exactly500 = "가".repeat(500);
        ContentChatSendRequest request = new ContentChatSendRequest(exactly500);

        Set<ConstraintViolation<ContentChatSendRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("정상 content면 검증 통과")
    void validate_validContent_passes() {
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");

        Set<ConstraintViolation<ContentChatSendRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }



}
