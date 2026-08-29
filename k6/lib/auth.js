import http from 'k6/http';
import { check } from 'k6';

export function setup() {
    const tokens = [];
    for (let i = 0; i < ACCOUNT_COUNT; i++) {
        const token = signIn(BASE_URL, `k6-watch-${i}@loadtest.local`, 'Load1234!test');
        if (token) tokens.push(token);
    }
    if (tokens.length < ACCOUNT_COUNT) {
        throw new Error(`토큰 확보 실패: ${tokens.length}/${ACCOUNT_COUNT}`);
    }
    return { tokens };
}

export function signIn(baseUrl, email, password) {
    const jar = new http.CookieJar();

    const csrfUrl = `${baseUrl}/api/auth/csrf-token`;
    const csrfRes = http.get(csrfUrl, { jar });
    check(csrfRes, { 'csrf-token 204': (r) => r.status === 204 });

    // 응답의 Set-Cookie가 아니라 jar의 현재 상태를 읽는다.
    const jarCookies = jar.cookiesForURL(csrfUrl);
    const xsrfToken = jarCookies['XSRF-TOKEN'] ? jarCookies['XSRF-TOKEN'][0] : null;

    const signInRes = http.post(
        `${baseUrl}/api/auth/sign-in`,
        JSON.stringify({ email, password }),
        {
            jar,
            headers: {
                'Content-Type': 'application/json',
                'X-XSRF-TOKEN': xsrfToken,
            },
        },
    );

    check(signInRes, { 'sign-in 200': (r) => r.status === 200 });
    if (signInRes.status !== 200) {
        console.log(`SIGNIN FAIL ${email} status=${signInRes.status} body=${signInRes.body}`);
        return null;
    }

    return signInRes.json('accessToken');
}
