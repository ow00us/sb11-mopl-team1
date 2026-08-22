package com.mopl.watchingsession.presence;

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
import org.springframework.test.context.TestPropertySource;
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
@ActiveProfiles("test")
@TestPropertySource(properties = "watching-session.content-existence-cache-ttl=200ms")
@Testcontainers
@EnableConfigurationProperties(WatchingSessionProperties.class)
public class ContentExistenceCacheTtlExpiryTest {

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
    void clearCacheKeys() {
        stringRedisTemplate.delete(KEY);
    }

    @Test
    @DisplayName("캐시 TTL이 지나면 다시 DB를 조회한다")
    void exists_refetchesFromDb_afterTtlExpires() throws InterruptedException {
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        cache.exists(CONTENT_ID);

        Thread.sleep(300);
        cache.exists(CONTENT_ID);

        verify(contentRepository, times(2)).existsById(CONTENT_ID);
    }
}
