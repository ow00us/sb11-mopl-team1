package com.mopl.user.security.oauth;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 인가 요청을 Redis 에 두는 구현체입니다.
 *
 * <p>백엔드 인스턴스가 모두 같은 Redis 를 보므로, 인가를 시작한 인스턴스와 callback 을 받은
 * 인스턴스가 달라도 같은 값을 찾습니다.
 */
@Component
@RequiredArgsConstructor
public class RedisOAuth2AuthorizationRequestStore implements OAuth2AuthorizationRequestStore {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void save(String key, String value, Duration timeToLive) {
        stringRedisTemplate.opsForValue().set(key, value, timeToLive);
    }

    @Override
    public String find(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * {@code GETDEL} 한 번으로 읽으면서 지웁니다.
     *
     * <p>{@code GET} 뒤에 {@code DEL} 을 부르면 그 사이에 들어온 두 번째 요청이 같은 값을
     * 읽어 갑니다. 인가 코드 재사용을 막으려면 소비가 한 번만 성공해야 합니다.
     */
    @Override
    public String findAndRemove(String key) {
        return stringRedisTemplate.opsForValue().getAndDelete(key);
    }
}
