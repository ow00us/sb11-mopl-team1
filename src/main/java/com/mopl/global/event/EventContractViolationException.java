package com.mopl.global.event;

/**
 * 이벤트가 계약을 위반해 재시도로는 해소될 수 없는 경우에 던집니다.
 *
 * <p>지원하지 않는 type·version, 필수 payload 누락처럼 같은 메시지를 다시 처리해도
 * 결과가 같은 상황이 대상입니다. 공통 오류 처리에서 재시도하지 않고 곧바로 DLT 로
 * 보냅니다.
 *
 * <p>일시적인 DB·네트워크 오류에는 이 예외를 쓰지 않습니다. 그 경우는 재시도 대상이며
 * 일반 예외로 전파해야 합니다.
 */
public class EventContractViolationException extends RuntimeException {

    public EventContractViolationException(String message) {
        super(message);
    }

    public EventContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
