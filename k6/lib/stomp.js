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
        const commandEnd = f.indexOf('\n');
        const command = (commandEnd >= 0 ? f.slice(0, commandEnd) : f).replace(/\r$/, '');
        const bodyIndex = f.indexOf('\n\n');
        const body = bodyIndex >= 0 ? f.slice(bodyIndex + 2) : '';
        const headerBlock = commandEnd >= 0
            ? f.slice(commandEnd + 1, bodyIndex >= 0 ? bodyIndex : f.length)
            : '';
        const headers = {};

        for (const rawLine of headerBlock.split('\n')) {
            const line = rawLine.replace(/\r$/, '');
            const separator = line.indexOf(':');
            if (separator <= 0) continue;
            headers[line.slice(0, separator)] = line.slice(separator + 1);
        }

        return { command, headers, body };
    });
}
