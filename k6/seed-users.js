import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const COUNT = parseInt(__ENV.COUNT || '200', 10);

export const options = {
    scenarios: {
        seed: {
            executor: 'shared-iterations',
            vus: 10,
            iterations: COUNT,
            maxDuration: '5m',
        },
    },
};

export default function () {
    const idx = exec.scenario.iterationInTest; // 0..COUNT-1, 전역 단조 증가
    const email = `k6-watch-${idx}@loadtest.local`;
    const password = 'Load1234!test';

    const csrfRes = http.get(`${BASE_URL}/api/auth/csrf-token`);
    const xsrfCookie = csrfRes.cookies['XSRF-TOKEN'];
    const xsrfToken = xsrfCookie && xsrfCookie[0] ? xsrfCookie[0].value : null;

    const res = http.post(
        `${BASE_URL}/api/users`,
        JSON.stringify({ name: `k6user${idx}`, email, password }),
        { headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrfToken } },
    );

    // 이미 만들어진 계정이면 409 — 재실행 시 정상
    check(res, { 'created or already exists': (r) => r.status === 201 || r.status === 409 });

    sleep(0.05);
}
