const NULL_BYTE = '\x00';

export function buildFrame(command, headers, body = '') {
    const headerLines = Object.entries(headers)
    .map(([k, v]) => `${k}:${v}`)
    .join('\n');
    return `${command}\n${headerLines}\n\n${body}${NULL_BYTE}`;
}

export function connectFrame(accessToken) {
    return buildFrame('CONNECT', {
        'accept-version': '1.2',
        'heart-beat': '0,0',
        Authorization: `Bearer ${accessToken}`,
    });
}

export function subscribeFrame(destination, subscriptionId) {
    return buildFrame('SUBSCRIBE', { id: subscriptionId, destination });
}

export function sendFrame(destination, body) {
    return buildFrame('SEND', { destination, 'content-type': 'application/json' }, body);
}

// 서버가 보낸 원시 텍스트를 프레임 단위로 분리 (NULL 바이트 구분)
export function parseFrames(raw) {
    return raw
    .split(NULL_BYTE)
    .filter((f) => f.trim().length > 0)
    .map((f) => {
        const lines = f.split('\n').filter((l) => l.length > 0 || f.indexOf(l) === 0);
        const command = lines[0];
        const bodyIndex = f.indexOf('\n\n');
        const body = bodyIndex >= 0 ? f.slice(bodyIndex + 2) : '';
        return { command, body };
    });
}
