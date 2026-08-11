package com.mopl.user.controller;

import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.service.SignInResult;
import com.mopl.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
     * Service가 발급한 Refresh Token 원문을
     * 보안 속성이 적용된 HttpOnly Cookie로 변환
     */
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;

    /**
     * 이메일과 비밀번호로 로그인하고 Access Token과 Refresh Token을 반환
     *
     * @Valid가 SignInRequest의 Bean Validation을 실행
     * 이메일 또는 비밀번호 형식이 잘못되면 서비스 호출 전 400 Bad Request가 반환
     *
     * @param request JSON 형식의 로그인 요청
     * @return 사용자 정보와 Access Token이 담긴 JSON 본문, Refresh Token이 담긴 Set-Cookie 헤더와 200 OK
     */
    @PostMapping("/sign-in")
    public ResponseEntity<JwtDto> signIn(
        @Valid @RequestBody SignInRequest request
    ) {
        /*
         * AuthService는 JSON 응답용 JwtDto와 Cookie 전달용
         * Refresh Token 발급 결과를 분리하여 반환
         */
        SignInResult result =
            authService.signIn(request);

        /*
         * Refresh Token 원문은 JSON 본문에 넣지 않고
         * HttpOnly Cookie로 변환
         */
        ResponseCookie refreshTokenCookie =
            refreshTokenCookieFactory.create(
                result.issuedRefreshToken().rawToken()
            );

        /*
         * JwtDto만 JSON 본문으로 반환하고 Refresh Token은
         * Set-Cookie 응답 헤더를 통해 전달
         */
        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshTokenCookie.toString()
            )
            .body(result.jwtDto());
    }
}
