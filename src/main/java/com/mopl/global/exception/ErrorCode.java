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
    CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "CONTENT_404_1", "존재하지 않는 콘텐츠입니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_405_1", "지원하지 않는 요청 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_415_1", "지원하지 않는 요청 형식입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "COMMON_406_1", "요청한 응답 형식으로는 표현할 수 없습니다."),
    FOLLOW_SELF(HttpStatus.BAD_REQUEST, "FOLLOW_400_1", "자기 자신은 팔로우할 수 없습니다."),
    REQUEST_CONFLICT(HttpStatus.CONFLICT, "COMMON_409_1", "요청이 다른 처리와 충돌했습니다."),
    EMAIL_DUPLICATE(HttpStatus.CONFLICT, "USER_409_1", "이미 사용 중인 이메일입니다."),
    LOCAL_CREDENTIAL_ALREADY_EXISTS(HttpStatus.CONFLICT, "USER_409_2", "이미 이메일·비밀번호 로그인 수단이 등록되어 있습니다."),
    LOCAL_CREDENTIAL_NOT_FOUND(HttpStatus.CONFLICT, "USER_409_3", "이메일·비밀번호 로그인 수단이 등록되어 있지 않습니다."),
    EMAIL_VERIFICATION_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "USER_429_1", "잠시 후 인증 코드를 다시 요청해주세요."),
    EMAIL_VERIFICATION_INVALID(HttpStatus.BAD_REQUEST, "USER_400_1", "인증 코드가 올바르지 않거나 만료되었습니다."),
    OAUTH_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "OAUTH_404_1", "연결된 OAuth 계정을 찾을 수 없습니다."),
    OAUTH_ACCOUNT_CONFLICT(HttpStatus.CONFLICT, "OAUTH_409_1", "OAuth 계정 연결이 다른 사용자 또는 계정과 충돌했습니다."),
    OAUTH_LAST_LOGIN_METHOD(HttpStatus.CONFLICT, "OAUTH_409_2", "마지막 로그인 수단은 연결 해제할 수 없습니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "REVIEW_404_1", "존재하지 않는 리뷰입니다."),
    REVIEW_DUPLICATE(HttpStatus.CONFLICT, "REVIEW_409_1", "이미 작성한 리뷰가 존재합니다."),
    DIRECT_MESSAGE_INVALID_STATE(HttpStatus.INTERNAL_SERVER_ERROR, "DM_500_1", "DM 데이터 상태가 올바르지 않습니다."),
    IMAGE_UPLOAD_REJECTED(HttpStatus.BAD_REQUEST, "IMAGE_400_1", "이미지 파일이 올바르지 않습니다."),
    IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_500_1", "이미지를 저장하지 못했습니다."),
    DIRECT_MESSAGE_RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "DM_429_1", "메시지를 너무 빠르게 전송하고 있습니다."),
    OUTBOX_EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "OUTBOX_404_1", "존재하지 않는 Outbox 이벤트입니다."),
    OUTBOX_EVENT_NOT_FAILED(HttpStatus.CONFLICT, "OUTBOX_409_1", "최종 실패 상태가 아닌 Outbox 이벤트입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500_1", "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
