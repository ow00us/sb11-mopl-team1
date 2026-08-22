package com.mopl.global.realtime;

/**
 * 수신한 실시간 메시지를 전달하지 않고 버린 이유입니다.
 *
 * <p>버렸다는 사실만으로는 운영 판단이 서지 않습니다. 자기 인스턴스 메시지와 중복은 설계대로
 * 동작하고 있다는 뜻이고, 형식 오류와 필수 값 누락은 발행 쪽이나 채널을 지나는 계약이 깨졌다는
 * 뜻입니다. 앞의 둘은 늘 올라가는 것이 정상이고 뒤의 둘은 0이어야 합니다.
 */
public enum RealtimeRelayDiscardReason {

    /** JSON 으로 읽지 못했습니다. */
    MALFORMED("malformed"),

    /** 읽었지만 계약이 요구하는 값이 비어 있습니다. */
    INCOMPLETE("incomplete"),

    /** 자기 인스턴스가 발행한 메시지입니다. 이미 자기 연결로 보냈습니다. */
    SELF("self"),

    /** 같은 messageId 를 이미 전달했습니다. */
    DUPLICATE("duplicate");

    private final String tag;

    RealtimeRelayDiscardReason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
