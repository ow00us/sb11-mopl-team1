package com.mopl.global.security;

import com.mopl.user.dto.UserLockUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SecurityPolicyProbeController {

    @PostMapping({
        "/api/users",
        "/api/auth/sign-in",
        "/api/auth/sign-out"
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publicOrAuthenticationApi() {
    }

    @GetMapping("/api/security-policy/protected")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void protectedApi() {
    }

    /**
     * 관리자 계정 잠금 API의 보안 정책을 검증하기 위한 테스트 전용 경로
     *
     * 실제 UserController와 동일한 경로와 요청 DTO 검증 조건을 사용
     * 이를 통해 Spring Security가 DTO 역직렬화와 @Valid 검증보다 먼저
     * 관리자 권한을 검사하는지 확인할 수 있다.
     *
     * 이 컨트롤러는 테스트 소스에만 존재하므로 실제 애플리케이션에는
     * 포함되지 않는다.
     *
     * @param request 변경할 계정 잠금 상태
     */
    @PatchMapping("/api/users/{userId}/locked")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateLocked(
        @Valid @RequestBody UserLockUpdateRequest request
    ) {
    }

    @PostMapping("/ws/security-policy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void webSocketHandshake() {
    }
}
