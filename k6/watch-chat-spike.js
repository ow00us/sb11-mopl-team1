import { WebSocket } from 'k6/websockets';
import { Trend, Counter, Rate } from 'k6/metrics';
import { check } from 'k6';
import { setTimeout, setInterval, clearInterval } from 'k6/timers';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.3/index.js';
import { signIn } from './lib/auth.js';
import { connectFrame, subscribeFrame, sendFrame, parseFrames } from './lib/stomp.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = (__ENV.WS_URL || 'ws://localhost:8080') + '/ws/websocket';
const CONTENT_ID = __ENV.CONTENT_ID; // 사전에 만들어둔 고정 콘텐츠 UUID
const ACCOUNT_COUNT = parseInt(__ENV.ACCOUNT_COUNT || '200', 10);

// 지표
const connectDuration = new Trend('watch_connect_duration', true);
const watchSubAckDuration = new Trend('watch_subscribe_ack_duration', true);
const chatDeliveryDelay = new Trend('chat_delivery_delay', true);
const errorRate = new Rate('stomp_error_rate');
const chatSentCounter = new Counter('chat_sent_total');
const chatReceivedCounter = new Counter('chat_received_total');

export const options = {
    scenarios: {
        warmup: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [{ duration: '20s', target: 20 }, { duration: '20s', target: 0 }],
            tags: { phase: 'warmup' },
        },
        spike: {
            executor: 'ramping-vus',
            startTime: '45s',
            startVUs: 0,
            gracefulRampDown: '3m',   // 추가: 중단 대신 세션이 끝날 때까지 기다림
            stages: [
                { duration: '30s', target: 50 },
                { duration: '30s', target: 150 },
                { duration: '1m', target: parseInt(__ENV.PEAK_VUS || '300', 10) },
                { duration: '2m', target: parseInt(__ENV.PEAK_VUS || '300', 10) },
                { duration: '30s', target: 0 },
            ],
            tags: { phase: 'measure' },
        },
    },
};

export function setup() {
    // VU마다 다른 계정을 쓰도록 토큰을 미리 확보. 로그인 자체는 측정 대상이 아니다.
    const tokens = [];
    for (let i = 0; i < ACCOUNT_COUNT; i++) {
        const token = signIn(BASE_URL, `k6-watch-${i}@loadtest.local`, 'Load1234!test');
        tokens.push(token);
    }
    return { tokens };
}

export default function (data) {
    const token = data.tokens[(__VU - 1) % data.tokens.length];
    const connectedAt = Date.now();

    const ws = new WebSocket(WS_URL);
    const timers = [];
    let watchSubStart = 0;
    let ackRecorded = false;
    let sawError = false;

    ws.addEventListener('open', () => {
        connectDuration.add(Date.now() - connectedAt);
        ws.send(connectFrame(token));
    });

    ws.addEventListener('message', (event) => {
        for (const frame of parseFrames(event.data)) {
            if (frame.command === 'CONNECTED') {
                watchSubStart = Date.now();
                ws.send(subscribeFrame(`/sub/contents/${CONTENT_ID}/watch`, `sub-watch-${__VU}`));
                ws.send(subscribeFrame(`/sub/contents/${CONTENT_ID}/chat`, `sub-chat-${__VU}`));

                timers.push(setInterval(() => {
                    ws.send(sendFrame(`/pub/contents/${CONTENT_ID}/watch/heartbeat`, '{}'));
                }, 20000));

                timers.push(setInterval(() => {
                    ws.send(sendFrame(`/pub/contents/${CONTENT_ID}/chat`,
                        JSON.stringify({ content: `t:${Date.now()}:VU${__VU}` })));
                    chatSentCounter.add(1);
                }, 7000 + Math.floor(Math.random() * 3000)));
            }

            if (frame.command === 'MESSAGE') {
                if (!ackRecorded && watchSubStart) {
                    watchSubAckDuration.add(Date.now() - watchSubStart);
                    ackRecorded = true;
                }
                try {
                    const body = JSON.parse(frame.body);
                    const match = /^t:(\d+):/.exec(body.content || '');
                    if (match) {
                        chatDeliveryDelay.add(Date.now() - parseInt(match[1], 10));
                        chatReceivedCounter.add(1);
                    }
                } catch (e) {
                    // watch 채널 MESSAGE는 페이로드 형식이 달라 파싱 실패가 정상
                }
            }

            if (frame.command === 'ERROR') {
                sawError = true;
                console.log(`STOMP ERROR: ${frame.body}`);
            }
        }
    });

    ws.addEventListener('error', () => { sawError = true; });

    ws.addEventListener('close', () => {
        timers.forEach(clearInterval);
        errorRate.add(sawError);
    });

    // sleep 대신 타이머로 끝낸다. 이벤트 루프가 비어야 이터레이션이 종료된다.
    setTimeout(() => {
        timers.forEach(clearInterval);
        ws.close();
    }, 150000);
}

export function handleSummary(data) {
    return {
        'results/summary.json': JSON.stringify(data, null, 2),
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
    };
}
