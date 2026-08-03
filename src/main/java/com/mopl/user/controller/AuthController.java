package com.mopl.user.controller;

import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일 로그인 HTTP API를 처리하는 Controller
 *
 * Controller는 JSON 요청·응답과 입력값 검증만 담당
 * 실제 인증과 JWT 발급 규칙은 AuthService에 위임
 *
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 이메일과 비밀번호로 로그인하고 JWT 액세스 토큰을 반환
     *
     * @Valid가 SignInRequest의 Bean Validation을 실행
     * 이메일 또는 비밀번호 형식이 잘못되면 서비스 호출 전 400 Bad Request가 반환
     *
     * @param request JSON 형식의 로그인 요청
     * @return JSON 형식의 JWT 액세스 토큰과 200 OK
     */
    @PostMapping("/sign-in")
    public ResponseEntity<JwtDto> signIn(
        @Valid @RequestBody SignInRequest request
    ) {
        JwtDto response = authService.signIn(request);

        return ResponseEntity.ok(response);
    }
}
