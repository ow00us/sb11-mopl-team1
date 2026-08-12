package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

public class WatchingSessionPresenceWriterTest {

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-1";
    private static final String EXPECTED_KEY = "mopl:presence:watcher:" + WATCHER_ID;

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);

    private final WatchingSessionPresenceWriter writer = new WatchingSessionPresenceWriter(redisTemplate);

    @Test
    @DisplayName("write()는 watcherId 기준 키로 presence 값을 TTL과 함께 저장한다")
    void write_storesPresenceWithKeyAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Instant startedAt = Instant.now();
        Duration ttl = Duration.ofMinutes(30);

        writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, startedAt, ttl);

        WatchingPresence expected = new WatchingPresence(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, startedAt);
        verify(valueOperations).set(eq(EXPECTED_KEY), eq(expected), eq(ttl));
    }

    @Test
    @DisplayName("write() 도중 Redis 예외가 발생해도 호출자에게 전파되지 않는다")
    void write_isolatesRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis 연결 끊김"));

        assertThatCode(() ->
            writer.write(WATCHER_ID, CONTENT_ID, SESSION_ID, SUBSCRIPTION_ID, Instant.now(), Duration.ofMinutes(30)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("delete()는 watcherId 기준 키를 삭제한다")
    void delete_removesKeyByWatcherId() {
        writer.delete(WATCHER_ID);

        verify(redisTemplate).delete(EXPECTED_KEY);
    }

    @Test
    @DisplayName("delete() 도중 Redis 예외가 발생해도 호출자에게 전파되지 않는다")
    void delete_isolatesRedisFailure() {
        doThrow(new RuntimeException("Redis 연결 끊김")).when(redisTemplate).delete(any(String.class));

        assertThatCode(() -> writer.delete(WATCHER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("renew()는 watcherId 기준 키에 TTL을 재설정하고 true를 반환한다")
    void renew_resetsTtlAndReturnsTrue_whenKeyExists() {
        when(redisTemplate.expire(eq(EXPECTED_KEY), any(Duration.class))).thenReturn(true);
        Duration ttl = Duration.ofSeconds(60);

        boolean result = writer.renew(WATCHER_ID, ttl);

        assertThat(result).isTrue();
        verify(redisTemplate).expire(EXPECTED_KEY, ttl);
    }

    @Test
    @DisplayName("renew()는 키가 없으면 false를 반환하고 새로 생성하지 않는다")
    void renew_returnsFalse_whenKeyDoesNotExists() {
        when(redisTemplate.expire(eq(EXPECTED_KEY), any(Duration.class))).thenReturn(false);

        boolean result = writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("renew()는 expire()가 null을 반홚해도 false로 안전하게 처리한다")
    void renew_returnsFalse_whenExpireReturnsNull() {
        when(redisTemplate.expire(eq(EXPECTED_KEY), any(Duration.class))).thenReturn(null);

        boolean result = writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("renew() 도중 Redis 예외가 발생해도 호출자에게 전파되지 않고 false를 반환한다")
    void renew_isolatesRedisFailure() {
        when(redisTemplate.expire(eq(EXPECTED_KEY), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis 연결 끊김"));

        boolean result = writer.renew(WATCHER_ID, Duration.ofSeconds(60));

        assertThat(result).isFalse();
    }

}
