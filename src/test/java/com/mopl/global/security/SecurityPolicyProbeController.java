package com.mopl.global.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/ws/security-policy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void webSocketHandshake() {
    }
}
