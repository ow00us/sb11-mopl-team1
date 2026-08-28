import { WebSocket } from 'k6/websockets';
import { Trend, Counter, Rate } from 'k6/metrics';
import encoding from 'k6/encoding';
import { setTimeout, setInterval, clearInterval } from 'k6/timers';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.3/index.js';
import { signIn } from './lib/auth.js';
import { connectFrame, subscribeFrame, sendFrame, parseFrames } from './lib/stomp.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = (__ENV.WS_URL || 'ws://localhost:8080') + '/ws/websocket';
const CONTENT_ID = __ENV.CONTENT_ID; // 사전에 만들어둔 고정 콘텐츠 UUID
const ACCOUNT_COUNT = parseInt(__ENV.ACCOUNT_COUNT || '200', 10);
const PEAK_VUS = parseInt(__ENV.PEAK_VUS || '300', 10);
const WARMUP_VUS = 20;
const RAMP_1_VUS = Math.min(50, PEAK_VUS);
const RAMP_2_VUS = Math.min(150, PEAK_VUS);

// 지표
const connectDuration = new Trend('watch_connect_duration', true);
const watchSubAckDuration = new Trend('watch_subscribe_ack_duration', true);
const chatDeliveryDelay = new Trend('chat_delivery_delay', true);
const errorRate = new Rate('stomp_error_rate');
const watchErrorRate = new Rate('watch_error_rate');
const chatErrorRate = new Rate('chat_error_rate');
const sessionAttemptsCounter = new Counter('session_attempts_total');
const chatSentCounter = new Counter('chat_sent_total');
const chatReceivedCounter = new Counter('chat_received_total');
const chatBacklogReceivedCounter = new Counter('chat_backlog_received_total');
const chatEchoCounter = new Counter('chat_echo_total');

// warmup을 용량 지표에 섞지 않도록 spike 전용 지표를 별도로 기록한다.
const measureConnectDuration = new Trend('measure_watch_connect_duration', true);
const measureWatchSubAckDuration = new Trend('measure_watch_subscribe_ack_duration', true);
const measureChatDeliveryDelay = new Trend('measure_chat_delivery_delay', true);
const measureErrorRate = new Rate('measure_stomp_error_rate');
const measureWatchErrorRate = new Rate('measure_watch_error_rate');
const measureChatErrorRate = new Rate('measure_chat_error_rate');
const measureSessionAttemptsCounter = new Counter('measure_session_attempts_total');
const measureChatSentCounter = new Counter('measure_chat_sent_total');
const measureChatReceivedCounter = new Counter('measure_chat_received_total');
const measureChatBacklogReceivedCounter = new Counter('measure_chat_backlog_received_total');
const measureChatEchoCounter = new Counter('measure_chat_echo_total');

export const options = {
    scenarios: {
        warmup: {
            executor: 'per-vu-iterations',
            vus: WARMUP_VUS,
            iterations: 1,
            maxDuration: '30s',
            gracefulStop: '10s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        spike: {
            executor: 'ramping-vus',
            startTime: '45s',
            startVUs: 0,
            gracefulRampDown: '3m',
            gracefulStop: '3m',
            stages: [
                { duration: '30s', target: RAMP_1_VUS },
                { duration: '30s', target: RAMP_2_VUS },
                { duration: '1m', target: PEAK_VUS },
                { duration: '2m', target: PEAK_VUS },
                { duration: '30s', target: 0 },
            ],
            tags: { phase: 'measure' },
        },
    },
};

export function setup() {
    // VU마다 다른 계정과 watcherId를 미리 확보. 로그인 자체는 측정 대상이 아니다.
    const accounts = [];
    for (let i = 0; i < ACCOUNT_COUNT; i++) {
        const token = signIn(BASE_URL, `k6-watch-${i}@loadtest.local`, 'Load1234!test');
        if (!token) continue;

        const tokenParts = token.split('.');
        if (tokenParts.length !== 3) continue;

        try {
            const payload = JSON.parse(encoding.b64decode(tokenParts[1], 'rawurl', 's'));
            if (payload.sub) accounts.push({ token, watcherId: payload.sub });
        } catch (e) {
            // 아래 개수 검증에서 setup 전체를 실패시킨다.
        }
    }
    if (accounts.length !== ACCOUNT_COUNT) {
        throw new Error(`계정 정보 확보 실패: ${accounts.length}/${ACCOUNT_COUNT}`);
    }
    return { accounts };
}

function runSession(data, sessionDurationMs, measure) {
    const account = data.accounts[(__VU - 1) % data.accounts.length];
    const connectedAt = Date.now();

    const timers = [];
    let watchSubStart = 0;
    let chatSubStart = 0;
    let socketOpened = false;
    let stompConnected = false;
    let ackRecorded = false;
    let sawError = false;
    let chatEchoRecorded = false;
    let closeRequested = false;
    let outcomeRecorded = false;

    sessionAttemptsCounter.add(1);
    if (measure) measureSessionAttemptsCounter.add(1);

    const recordOutcome = () => {
        if (outcomeRecorded) return;
        const watchFailed = sawError || !socketOpened || !stompConnected || !ackRecorded;
        const chatFailed = sawError || !chatEchoRecorded;
        errorRate.add(watchFailed || chatFailed);
        watchErrorRate.add(watchFailed);
        chatErrorRate.add(chatFailed);
        if (measure) {
            measureErrorRate.add(watchFailed || chatFailed);
            measureWatchErrorRate.add(watchFailed);
            measureChatErrorRate.add(chatFailed);
        }
        outcomeRecorded = true;
    };

    let ws;
    try {
        ws = new WebSocket(WS_URL);
    } catch (e) {
        sawError = true;
        recordOutcome();
        return;
    }

    const sendSafely = (frame) => {
        try {
            ws.send(frame);
        } catch (e) {
            sawError = true;
        }
    };

    ws.addEventListener('open', () => {
        socketOpened = true;
        const duration = Date.now() - connectedAt;
        connectDuration.add(duration);
        if (measure) measureConnectDuration.add(duration);
        sendSafely(connectFrame(account.token));
    });

    ws.addEventListener('message', (event) => {
        for (const frame of parseFrames(event.data)) {
            if (frame.command === 'CONNECTED') {
                stompConnected = true;
                watchSubStart = Date.now();
                chatSubStart = watchSubStart;
                sendSafely(subscribeFrame(`/sub/contents/${CONTENT_ID}/watch`, `sub-watch-${__VU}`));
                sendSafely(subscribeFrame(`/sub/contents/${CONTENT_ID}/chat`, `sub-chat-${__VU}`));

                timers.push(setInterval(() => {
                    sendSafely(sendFrame(`/pub/contents/${CONTENT_ID}/watch/heartbeat`, '{}'));
                }, 20000));

                timers.push(setInterval(() => {
                    sendSafely(sendFrame(`/pub/contents/${CONTENT_ID}/chat`,
                        JSON.stringify({ content: `t:${Date.now()}:VU${__VU}` })));
                    chatSentCounter.add(1);
                    if (measure) measureChatSentCounter.add(1);
                }, 7000 + Math.floor(Math.random() * 3000)));
            }

            if (frame.command === 'MESSAGE') {
                const subscription = frame.headers.subscription;

                try {
                    const body = JSON.parse(frame.body);

                    if (!ackRecorded && watchSubStart && subscription === `sub-watch-${__VU}`
                        && body.type === 'JOIN'
                        && body.watchingSessionDto?.watcher?.userId === account.watcherId) {
                        const duration = Date.now() - watchSubStart;
                        watchSubAckDuration.add(duration);
                        if (measure) measureWatchSubAckDuration.add(duration);
                        ackRecorded = true;
                    }

                    if (subscription !== `sub-chat-${__VU}`) continue;

                    const match = /^t:(\d+):/.exec(body.content || '');
                    if (match) {
                        const sentAt = parseInt(match[1], 10);
                        if (sentAt < chatSubStart) {
                            chatBacklogReceivedCounter.add(1);
                            if (measure) measureChatBacklogReceivedCounter.add(1);
                            continue;
                        }
                        const delay = Date.now() - sentAt;
                        chatDeliveryDelay.add(delay);
                        if (measure) measureChatDeliveryDelay.add(delay);
                        chatReceivedCounter.add(1);
                        if (measure) measureChatReceivedCounter.add(1);

                        if ((body.content || '').endsWith(`:VU${__VU}`)) {
                            chatEchoRecorded = true;
                            chatEchoCounter.add(1);
                            if (measure) measureChatEchoCounter.add(1);
                        }
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
        if (!closeRequested) sawError = true;
        timers.forEach(clearInterval);
        recordOutcome();
    });

    // sleep 대신 타이머로 끝낸다. 이벤트 루프가 비어야 이터레이션이 종료된다.
    setTimeout(() => {
        timers.forEach(clearInterval);
        closeRequested = true;
        try {
            ws.close();
        } finally {
            recordOutcome();
        }
    }, sessionDurationMs);
}

export function warmup(data) {
    runSession(data, 20000, false);
}

export default function (data) {
    runSession(data, 150000, true);
}

export function handleSummary(data) {
    const sanitized = Object.assign({}, data);
    delete sanitized.setup_data;
    sanitized.measurement_id = __ENV.MEASUREMENT_ID || null;

    const summaryPath = __ENV.SUMMARY_PATH || 'results/summary.json';

    return {
        [summaryPath]: JSON.stringify(sanitized, null, 2),
        stdout: textSummary(data, { indent: ' ', enableColors: false }),
    };
}
