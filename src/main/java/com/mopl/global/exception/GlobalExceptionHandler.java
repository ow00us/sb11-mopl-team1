package com.mopl.global.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        return ResponseEntity.status(code.getStatus()).body(body);
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
        return ResponseEntity.status(code.getStatus()).body(body);
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
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        log.warn("MissingServletRequestPartException: {}", e.getRequestPartName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getRequestPartName(), "필수 요청 파트가 누락되었습니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), details);
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException", e);
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, "요청 본문을 파싱할 수 없습니다. 형식을 확인해주세요.", new HashMap<>());
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return ResponseEntity.status(code.getStatus()).body(body);
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
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolationException", e);
        ErrorCode code = ErrorCode.REQUEST_CONFLICT;
        ErrorResponse body = ErrorResponse.of(e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: {}", e.getParameterName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getParameterName(), "필수 파라미터입니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
            e.getClass().getSimpleName(), code, code.getMessage(), details);
        return ResponseEntity.status(code.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("MethodArgumentTypeMismatchException: {}", e.getName());
        Map<String, String> details = new HashMap<>();
        details.put(e.getName(), "파라미터 형식이 올바르지 않습니다.");
        ErrorCode code = ErrorCode.INVALID_INPUT;
        ErrorResponse body = ErrorResponse.of(
            e.getClass().getSimpleName(), code, code.getMessage(), details);
        return ResponseEntity.status(code.getStatus()).body(body);
    }
}
