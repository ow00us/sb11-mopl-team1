package com.mopl.watchingsession.presence;

import com.mopl.content.repository.ContentRepository;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentExistenceCache {

    private static final String KEY_TEMPLATE = "mopl:content:exists:%s";
    private static final String EXISTS_MARKER = "1";

    private final StringRedisTemplate stringRedisTemplate;
    private final ContentRepository contentRepository;
    private final WatchingSessionProperties watchingSessionProperties;

    /**
     * 캐시 적중 시 DB를 조회하지 않는다. Redis 조회 자체가 실패해도 존재 여부
     * 판정이 멈추면 안 되므로, 실패 시 곧바로 DB로 폴백한다(캐시는 최적화이고 원본이 아니다).
     */
    public boolean exists(UUID contentId) {
        String key = KEY_TEMPLATE.formatted(contentId);
        try {
            if (EXISTS_MARKER.equals(stringRedisTemplate.opsForValue().get(key))) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("콘텐츠 존재 캐시 조회 실패, DB로 폴백: contentId={}", contentId, e);
        }

        boolean exists = contentRepository.existsById(contentId);
        if (exists) {
            cacheSilently(key);
        }
        return exists;
    }

    private void cacheSilently(String key) {
        try {
            stringRedisTemplate.opsForValue()
                .set(key, EXISTS_MARKER, watchingSessionProperties.getContentExistenceCacheTtl());
        } catch (RuntimeException e) {
            log.warn("콘텐츠 존재 캐시 저장 실패, 다음 요청은 DB를 다시 조회함", e);
        }
    }

}
