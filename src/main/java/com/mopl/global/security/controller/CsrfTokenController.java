package com.mopl.global.security.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/csrf-token")
public class CsrfTokenController {

    @GetMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void getCsrfToken(CsrfToken csrfToken) {
        // 지연 생성된 토큰을 읽어 CookieCsrfTokenRepository가 응답 쿠키에 저장하게 합니다.
        csrfToken.getToken();
    }
}
