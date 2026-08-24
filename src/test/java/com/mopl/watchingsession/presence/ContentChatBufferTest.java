package com.mopl.watchingsession.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mopl.global.common.UserSummary;
import com.mopl.global.config.RedisConfig;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    RedisConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class,
    ContentChatBuffer.class,
})
@EnableConfigurationProperties(WatchingSessionProperties.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "watching-session.chat-buffer-size=3",
    "watching-session.chat-buffer-ttl=1s"
})
@Testcontainers
class ContentChatBufferTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String KEY = "mopl:chat:buffer:" + CONTENT_ID;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ContentChatBuffer contentChatBuffer;

    @BeforeEach
    void clearBufferKey() {
        stringRedisTemplate.delete(KEY);
    }

    private ContentChatDto messageOf(String content) {
        return new ContentChatDto(new UserSummary(UUID.randomUUID(), "우디", null), content);
    }

    @Test
    @DisplayName("append 후 recent는 오래된 순서로 메시지를 반환한다")
    void recent_returnsMessagesInInsertionOrder() {
        contentChatBuffer.append(CONTENT_ID, messageOf("첫번째"));
        contentChatBuffer.append(CONTENT_ID, messageOf("두번째"));

        List<ContentChatDto> result = contentChatBuffer.recent(CONTENT_ID);

        assertThat(result).extracting(ContentChatDto::content)
            .containsExactly("첫번째", "두번째");
    }

    @Test
    @DisplayName("상한(3)을 넘겨 4건을 추가하면 가장 오래된 메시지가 밀려난다")
    void append_evictsOldestMessage_whenExceedingBufferSize() {
        contentChatBuffer.append(CONTENT_ID, messageOf("1"));
        contentChatBuffer.append(CONTENT_ID, messageOf("2"));
        contentChatBuffer.append(CONTENT_ID, messageOf("3"));
        contentChatBuffer.append(CONTENT_ID, messageOf("4"));

        List<ContentChatDto> result = contentChatBuffer.recent(CONTENT_ID);

        assertThat(result).extracting(ContentChatDto::content)
            .containsExactly("2", "3", "4");
    }

    @Test
    @DisplayName("마지막 메시지 이후 TTL(1s)이 지나면 버퍼 키가 사라진다")
    void append_keyExpires_afterTtlSinceLastMessage() {
        contentChatBuffer.append(CONTENT_ID, messageOf("곧 사라짐"));
        assertThat(stringRedisTemplate.hasKey(KEY)).isTrue();

        await().atMost(3, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(stringRedisTemplate.hasKey(KEY)).isFalse());
    }

    @Test
    @DisplayName("메시지를 추가할 때마다 TTL이 재설정되어, 개별 TTL 안에 추가가 이어지면 버퍼가 유지된다")
    void append_renewsTtl_onEachAppend() throws InterruptedException {
        contentChatBuffer.append(CONTENT_ID, messageOf("1"));
        Thread.sleep(700);
        contentChatBuffer.append(CONTENT_ID, messageOf("2")); // TTL 재설정

        Thread.sleep(700); // 첫 append 기준으로는 이미 1.4s 지났지만 두 번째 append가 갱신했으므로 유지

        assertThat(stringRedisTemplate.hasKey(KEY)).isTrue();
    }

    @Test
    @DisplayName("버퍼가 비어 있으면 recent는 빈 리스트를 반환한다")
    void recent_returnsEmptyList_whenBufferIsEmpty() {
        List<ContentChatDto> result = contentChatBuffer.recent(CONTENT_ID);

        assertThat(result).isEmpty();
    }
}
