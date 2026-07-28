package com.mopl.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void 충돌_오류는_409와_안정적인_코드를_사용한다() {
        assertThat(ErrorCode.CONFLICT.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONFLICT.getCode()).isEqualTo("COMMON_409_1");
        assertThat(ErrorCode.CONFLICT.getMessage())
                .isEqualTo("현재 리소스 상태와 요청이 충돌합니다.");
    }

    @Test
    void 충돌_예외는_공통_핸들러에서_409_응답으로_변환된다() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response =
                handler.handleBusiness(new BusinessException(ErrorCode.CONFLICT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("COMMON_409_1");
    }
}
