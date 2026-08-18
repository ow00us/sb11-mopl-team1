package com.mopl.global.realtime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 이 프로세스를 가리키는 식별자입니다.
 *
 * <p>자기가 발행한 메시지를 되받았는지 판단하는 기준입니다. 두 인스턴스가 같은 값을 갖게
 * 되면 서로의 메시지를 자기 것으로 보고 버려 전달이 조용히 끊깁니다.
 *
 * <p>호스트 이름만으로는 부족합니다. 한 호스트에 여러 인스턴스를 띄우는 배포에서 값이
 * 겹칩니다. 사람이 읽을 수 있도록 호스트 이름을 앞에 두고 무작위 값을 붙입니다.
 */
@Component
public class RealtimeInstanceId {

    private final String value;

    public RealtimeInstanceId() {
        this.value = resolve();
    }

    RealtimeInstanceId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    private String resolve() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID();
    }
}
