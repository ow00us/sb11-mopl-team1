package com.mopl.global.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ErrorResponse body = ErrorResponse.of(
                e.getClass().getSimpleName(), code, code.getMessage(), new HashMap<>());
        return ResponseEntity.status(code.getStatus()).body(body);
    }
}
