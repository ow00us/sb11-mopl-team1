package com.mopl.user.security.oauth;

import java.time.Duration;

/**
 * OAuth2 인가 요청을 인스턴스 사이에서 공유하는 저장소입니다.
 *
 * <p>저장소를 인터페이스로 분리한 이유는 두 가지입니다. 인가 요청 직렬화와 state 처리 규칙은
 * 저장 매체와 상관없이 같아야 하고, 저장소 없이도 그 규칙을 검증할 수 있어야 합니다.
 */
public interface OAuth2AuthorizationRequestStore {

    /** 인가 요청을 저장합니다. 보관 기간이 지나면 스스로 사라져야 합니다. */
    void save(String key, String value, Duration timeToLive);

    /** 저장한 값을 읽습니다. 없으면 {@code null} 입니다. */
    String find(String key);

    /**
     * 값을 읽으면서 지웁니다. 없으면 {@code null} 입니다.
     *
     * <p>읽기와 지우기가 나뉘면 같은 state 로 동시에 들어온 두 요청이 모두 같은 인가 요청을
     * 받아 갑니다. 한 번의 연산이어야 합니다.
     */
    String findAndRemove(String key);
}
