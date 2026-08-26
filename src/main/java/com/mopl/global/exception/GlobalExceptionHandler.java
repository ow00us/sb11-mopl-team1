package com.mopl.global.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HashMap;
import java.util.Map;

/**
 * 애플리케이션 어디서 예외가 발생하든 한곳에서 받아, 항상 ErrorResponse 형태로 바꿔 응답합니다.
 * 덕분에 도메인마다 에러 응답 형식이 제각각이 되는 일을 막을 수 있습니다.
 *
 * 오류 응답은 요청의 Accept 헤더와 무관하게 항상 JSON 으로 내보냅니다. 이유는
 * {@link #jsonError(ErrorCode, ErrorResponse, HttpHeaders)} 에 적어 두었습니다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.warn("BusinessException: {} - {}", code.getCode(), e.getMessage());
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, e.getMessage(), e.getDetails());
        return jsonError(code, body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> details = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        Map<String, String> details = new HashMap<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            details.put(field, v.getMessage());
        }
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        log.warn("MissingServletRequestPartException: {}", e.getRequestPartName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getRequestPartName(), "필수 요청 파트가 누락되었습니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException", e);
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, "요청 본문을 파싱할 수 없습니다. 형식을 확인해주세요.", new HashMap<>());
        return jsonError(code, body);
    }

    /**
     * 매핑된 핸들러도 정적 리소스도 없는 경로입니다.
     *
     * 핸들러가 없으면 폴백으로 떨어져 500이 되는데, 서버 내부 오류가 아니라
     * 존재하지 않는 경로를 부른 요청이므로 404로 돌려줍니다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        ErrorCode code = ErrorCode.RESOURCE_NOT_FOUND;
        log.warn("NoResourceFound: {} {}", e.getHttpMethod(), e.getResourcePath());
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return jsonError(code, body);
    }

    /**
     * 경로는 있지만 그 메서드로는 매핑되지 않은 요청입니다.
     *
     * 405 응답은 허용 메서드를 Allow 헤더로 알려야 합니다. 예외가 ErrorResponse 를
     * 구현해 헤더를 만들어 주므로 그대로 실어 보냅니다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        log.warn("HttpRequestMethodNotSupported: {}", e.getMethod());
        Map<String, String> details = new HashMap<>();
        if (e.getSupportedHttpMethods() != null) {
            details.put("supportedMethods", e.getSupportedHttpMethods().toString());
        }
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body, e.getHeaders());
    }

    /**
     * 엔드포인트가 소비하지 않는 Content-Type 으로 들어온 요청입니다.
     *
     * PATCH 요청이면 예외가 Accept-Patch 헤더를 만들어 주므로 함께 실어 보냅니다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        ErrorCode code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        log.warn("HttpMediaTypeNotSupported: {}", e.getContentType());
        Map<String, String> details = new HashMap<>();
        if (!e.getSupportedMediaTypes().isEmpty()) {
            details.put("supportedMediaTypes", e.getSupportedMediaTypes().toString());
        }
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body, e.getHeaders());
    }

    /**
     * 서버가 만들 수 없는 표현을 Accept 로 요청한 경우입니다.
     *
     * 핸들러가 없으면 DefaultHandlerExceptionResolver 가 본문 없이 sendError 로
     * 처리하고, 그 뒤 컨테이너 오류 디스패치에서 다시 인증 검사가 돌아 상태 코드가
     * 401 로 바뀝니다. 원인과 무관한 상태 코드는 클라이언트가 토큰 만료로 오인해
     * 재로그인하게 만들므로 여기서 406 으로 확정합니다.
     *
     * 생산 가능한 표현을 details 로 알려 클라이언트가 Accept 를 고칠 수 있게 합니다.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        ErrorCode code = ErrorCode.NOT_ACCEPTABLE;
        log.warn("HttpMediaTypeNotAcceptable: supported={}", e.getSupportedMediaTypes());
        Map<String, String> details = new HashMap<>();
        if (!e.getSupportedMediaTypes().isEmpty()) {
            details.put("supportedMediaTypes", e.getSupportedMediaTypes().toString());
        }
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return jsonError(code, body);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
        Map<String, String> details = new HashMap<>();
        e.getParameterValidationResults().forEach(result -> {
            String parameterName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> details.put(
                parameterName != null
                    ? parameterName
                    : "parameter",
                error.getDefaultMessage()));
        });
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolationException", e);
        ErrorCode code = ErrorCode.REQUEST_CONFLICT;
        ErrorResponse body = ErrorResponse.of(e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return jsonError(code, body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: {}", e.getParameterName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getParameterName(), "필수 파라미터입니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
            e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("MethodArgumentTypeMismatchException: {}", e.getName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getName(), "파라미터 형식이 올바르지 않습니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
            e.getClass().getSimpleName(), code, code.getMessage(), details);
        return jsonError(code, body);
    }

    private ResponseEntity<ErrorResponse> jsonError(ErrorCode code, ErrorResponse body) {
        return jsonError(code, body, HttpHeaders.EMPTY);
    }

    /**
     * 오류 응답을 만듭니다. Content-Type 을 JSON 으로 못박는 것이 핵심입니다.
     *
     * Content-Type 을 지정하지 않으면 응답 직렬화가 요청의 Accept 협상을 다시
     * 거칩니다. 클라이언트가 JSON 을 허용하지 않는 Accept 를 보내면 ErrorResponse 를
     * 만들 수 있는 표현이 없어 예외가 이 핸들러 밖으로 빠져나가고, 컨테이너 오류
     * 디스패치로 넘어가면서 상태 코드가 원인과 무관하게 바뀝니다.
     *
     * 오류 응답은 협상 대상이 아니라 진단 정보이므로 항상 JSON 으로 내보냅니다.
     */
    private ResponseEntity<ErrorResponse> jsonError(ErrorCode code, ErrorResponse body, HttpHeaders headers) {
        return ResponseEntity.status(code.getStatus())
            .headers(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
