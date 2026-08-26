package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.config.RedisConfig;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    RedisConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class,
    ContentExistenceCache.class,
})
@EnableConfigurationProperties(WatchingSessionProperties.class)
@ActiveProfiles("test")
@Testcontainers
public class ContentExistenceCacheTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String KEY = "mopl:content:exists:" + CONTENT_ID;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private ContentRepository contentRepository;

    @Autowired
    private ContentExistenceCache cache;

    @BeforeEach
    void clearPresenceKeys() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("캐시 미스면 DB를 조회하고, 존재하면 캐시에 저장한다")
    void exists_missesCache_thenCachesOnDbHit() {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);

        boolean first = cache.exists(CONTENT_ID);
        boolean second = cache.exists(CONTENT_ID);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        verify(contentRepository, times(1)).existsById(CONTENT_ID); // 두 번째는 캐시 적중, DB 미조회
    }

    @Test
    @DisplayName("존재하지 않으면 캐싱하지 않아 다음 호출도 DB를 다시 조회한다")
    void exists_doesNotCache_whenContentAbsent() {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        cache.exists(CONTENT_ID);
        cache.exists(CONTENT_ID);

        verify(contentRepository, times(2)).existsById(CONTENT_ID);
    }
}
