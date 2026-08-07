package com.mopl.global.security.csrf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * CSRF 토큰 쿠키를 지우는 대신 새 토큰으로 교체하는 저장소입니다.
 *
 * 스프링 시큐리티는 인증이 성립한 요청에서 기존 CSRF 토큰을 무효화하려고
 * {@code saveToken(null, ...)} 을 호출합니다. {@link CookieCsrfTokenRepository} 는 이를
 * 빈 값 쿠키로 응답에 실어 브라우저에서 삭제되게 합니다. 이후 재발급은 지연 로딩이라
 * 아무도 토큰을 읽지 않으면 실행되지 않고, 삭제만 반영된 채 응답이 끝납니다.
 *
 * 그러면 다음 상태 변경 요청이 토큰 없이 전송되어 403 으로 실패합니다. 그 403 응답이
 * 새 토큰을 발급하므로 그 다음 요청은 성공하고, 결과적으로 성공과 실패가 번갈아
 * 나타납니다.
 *
 * 여기서는 삭제 요청을 즉시 새 토큰 발급으로 바꿉니다. 토큰이 교체된다는 점은 그대로
 * 유지하면서, 클라이언트가 토큰 없는 상태로 남지 않게 합니다.
 */
public class RotatingCookieCsrfTokenRepository implements CsrfTokenRepository {

    private final CookieCsrfTokenRepository delegate;

    public RotatingCookieCsrfTokenRepository(CookieCsrfTokenRepository delegate) {
        this.delegate = delegate;
    }

    /** 쿠키를 자바스크립트에서 읽을 수 있어야 헤더로 되돌려 보낼 수 있습니다. */
    public static RotatingCookieCsrfTokenRepository withHttpOnlyFalse() {
        return new RotatingCookieCsrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token != null) {
            delegate.saveToken(token, request, response);
            return;
        }

        // 삭제 대신 교체합니다. 지연 재발급을 기다리지 않고 이 응답에서 바로 내려보냅니다.
        delegate.saveToken(delegate.generateToken(request), request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
