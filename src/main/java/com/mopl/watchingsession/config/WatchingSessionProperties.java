package com.mopl.watchingsession.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 시청 세션 TTL 정책을 관리하는 설정 클래스.
 *
 * application.yml의 watching-session 설정을 자바 객체로 바인딩한다.
 * TTL을 Service 코드에 상수로 박아두지 않고 설정으로 분리해,
 * 테스트에서 짧은 TTL로 오버라이드해 실제 만료를 몇 초 안에 검증할 수 있게 한다
 *
 * 예:
 * watching-session:
 *   presence-ttl: 90s
 *   session-ttl: 120s
 *   heartbeat-interval: 20s
 *   sweep-interval: 5m
 */

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "watching-session")
@Validated
public class WatchingSessionProperties {


    /**
     * Redis presence 키의 TTL. heartbeat마다 이 값으로 재설정된다.
     */
    @NotNull
    private Duration presenceTtl;

    /**
     * DB 시청 세션 스냅샷(expires_at)의 만료 여유. heartbeat마다 현재 시각 + 이 값으로 연장된다.
     */
    @NotNull
    private Duration sessionTtl;

    /**
     * 클라이언트가 heartbeat를 보내는 주기. 서버는 이 값을 직접 사용하지 않고
     * (주기 결정은 클라이언트 책임), openapi 계약 문서와의 정합 확인용으로 보관한다.
     */
    @NotNull
    private Duration heartbeatInterval;

    /**
     * 만료된 스냅샷을 정리하는 배치 주기. 서버 재시작 등으로 end() 경로를 타지 못한 행을 주기적으로 걷어낸다.
     * 조회는 이미 expiresAt으로 걸러지므로 정합성에는 영향이 없어 짧게 잡을 필요가 없다.
     */
    @NotNull
    private Duration sweepInterval;

    /**
     * 콘텐츠 채팅 SEND의 최소 허용 간격. 이보다 빠른 전송은 조용히 무시된다.
     */
    @NotNull
    private Duration chatSendMinInterval;

    /**
     * watch 토픽 재구독(SUBSCRIBE)의 최소 허용 간격. 이보다 빠른 재구독은 조용히 무시된다.
     * 정상 흐름(페이지 진입, 재연결)은 이 값에 절대 닿지 않아야 한다.
     */
    @NotNull
    private Duration watchSubscribeMinInterval;

    /**
     * 한 WebSocket 연결이 동시에 유지할 수 있는 콘텐츠 채팅 구독 개수 상한.
     * 정상 사용(페이지당 1개)보다 넉넉히 크게 잡아 어뷰징만 걸리게 한다.
     */
    @NotNull
    @Positive
    private Integer chatSubscriptionLimit;

}
