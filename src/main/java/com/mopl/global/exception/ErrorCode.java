package com.mopl.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러를 구분하는 안정적인 식별자들을 모아 둔 곳입니다.
 * 자바 클래스 이름(exceptionName)은 리팩터링하면 바뀌어서 클라이언트가 믿고 분기하기 어렵기 때문에,
 * 여기 정의한 code 값을 기준으로 분기하도록 합니다.
 * 각 도메인에서 필요한 코드는 담당자가 이 enum에 추가하시면 됩니다. (예: REVIEW_DUPLICATE)
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_400_1", "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_401_1", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_403_1", "권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404_1", "리소스를 찾을 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500_1", "서버 오류가 발생했습니다."),

    // 대상 콘텐츠 없음
    CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "CONTENT_404_1", "존재하지 않는 콘텐츠입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
