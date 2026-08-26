package com.mopl.watchingsession.presence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * presence를 변경하지 않는 순수 조회 전용 컴포넌트입니다.
 *
 * 소유권 판정·삭제·연장 같은 쓰기 책임은 WatchingSessionPresenceWriter가 전담하고,
 * 이 클래스는 "지금 이 사용자가 이 콘텐츠를 보고 있는가"만 답한다
 *
 * 실패를 격리하지 않고 그대로 전파한다. 이 조회는 채팅 SEND의 인가 게이트로 쓰이므로,
 * 실패를 삼켜 기본값(허용/차단)으로 처리하면 그 자체가 보안 결정이 된다.
 * 실패는 호출자가 판단하도록 RuntimeException을 그대로 던진다.
 */
@Component
@RequiredArgsConstructor
public class WatchingSessionPresenceReader {

    // TYPE이 hash가 아니면(레거시 문자열, 키 없음) "시청 중 아님"과 동일하게 0을 반환한다.
    // 값 비교와 타입 확인을 한 번의 왕복으로 묶어 채팅 전송마다의 지연을 최소화한다.
    private static final String IS_WATCHING_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return 0
        end
        if redis.call('HGET', KEYS[1], 'contentId') == ARGV[1] then
          return 1
        end
        return 0
        """;

    private static final String COUNT_BY_CONTENT_LUA = """
        return redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[1], '+inf')
        """;

    private static final RedisScript<Long> IS_WATCHING_SCRIPT =
        new DefaultRedisScript<>(IS_WATCHING_LUA, Long.class);

    private static final RedisScript<Long> COUNT_BY_CONTENT_SCRIPT =
        new DefaultRedisScript<>(COUNT_BY_CONTENT_LUA, Long.class);


    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @return 해당 watcher의 presence가 존재하고, 그 콘텐츠가 contentId와 일치하면 true.
     *         presence 만료·미시청·다른 콘텐츠 시청 중이면 false.
     */
    public boolean isWatching(UUID watcherId, UUID contentId) {
        Long result = stringRedisTemplate.execute(
            IS_WATCHING_SCRIPT,
            List.of(WatchingSessionPresenceKey.of(watcherId)),
            contentId.toString());
        return Long.valueOf(1L).equals(result);
    }

    /**
     * @return 해당 콘텐츠를 현재 시청 중인 인원 수. presence가 만료된 멤버는
     *         ZCOUNT 조건에서 자동으로 빠지므로 별도 정리 없이 정확한 값을 반환한다.
     *         실패는 isWatching과 동일하게 호출자에게 그대로 전파한다.
     */
    public long countByContent(UUID contentId) {
        Long result = stringRedisTemplate.execute(
            COUNT_BY_CONTENT_SCRIPT,
            List.of(WatchingSessionPresenceKey.ofContent(contentId)),
            String.valueOf(Instant.now().toEpochMilli()));
        return result == null ? 0L : result;
    }
}
