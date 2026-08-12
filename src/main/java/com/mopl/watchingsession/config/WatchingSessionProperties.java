package com.mopl.watchingsession.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 시청 세션 TTL 정책을 관리하는 설정 클래스.
 *
 * application.yml의 watching-session 설정을 자바 객체로 바인딩한다.
 * TTL을 Service 코드에 상수로 박아두지 않고 설정으로 분리해,
 * 테스트에서 짧은 TTL로 오버라이드해 실제 만료를 몇 초 안에 검증할 수 있게 한다
 *
 * 예:
 * watching-session:
 *   presence-ttl: 60s
 *   session-ttl: 30m
 *   heartbeat-interval: 20s
 */

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "watching-session")
public class WatchingSessionProperties {


    /**
     * Redis presence 키의 TTL. heartbeat마다 이 값으로 재설정된다.
     */
    private Duration presenceTtl;

    /**
     * DB 시청 세션 스냅샷(expires_at)의 만료 여유. heartbeat마다 현재 시각 + 이 값으로 연장된다.
     */
    private Duration sessionTtl;

    /**
     * 클라이언트가 heartbeat를 보내는 주기. 서버는 이 값을 직접 사용하지 않고
     * (주기 결정은 클라이언트 책임), openapi 계약 문서와의 정합 확인용으로 보관한다.
     */
    private Duration heartbeatInterval;

}
