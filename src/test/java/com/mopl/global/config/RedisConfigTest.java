package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * RedisConfig가 등록한 RedisTemplate의 직렬화 계약을 검증합니다.
 *
 * 개별 도메인 로직(presence, 카운터 등)은 아직 없으므로, 여기서는 "공통 설정이 의도한 대로
 * 동작하는가"만 확인합니다. 도메인별 키 스키마·비즈니스 로직 테스트는 각 태스크에서 추가합니다.
 */
@SpringBootTest(classes = {
    RedisConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class
})
@ActiveProfiles("test")
@Testcontainers
public class RedisConfigTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    private record SampleValue(String name, Instant createdAt) {
    }

    @Test
    @DisplayName("키는 평문 문자열로 저장되어 StringRedisTemplate으로도 그대로 읽힌다")
    void key_isStoredAsPlainString() {
        // given
        String key = "mopl:test:plain-key";
        redisTemplate.opsForValue().set(key, "value");

        // when
        // 같은 커넥션 팩토리를 공유하는 StringRedisTemplate으로 같은 키 직접 조회. JDK 직렬화는 조회되지 않음
        Boolean exists = stringRedisTemplate.hasKey(key);

        // then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("JDK 직렬화를 쓰는 기본 RedisTemplate 빈은 생성되지 않는다")
    void defaultJdkSerializingTemplate_isNotCreated() {
        // when
        String[] redisTemplateBeanNames =
            applicationContext.getBeanNamesForType(RedisTemplate.class);

        // then
        assertThat(redisTemplateBeanNames)
            .containsExactlyInAnyOrder("redisTemplate", "stringRedisTemplate");

        RedisTemplate<?, ?> ourTemplate =
            (RedisTemplate<?, ?>) applicationContext.getBean("redisTemplate");
        assertThat(ourTemplate.getValueSerializer())
            .isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("Instant 필드를 가진 record를 저장하고 조회하면 원본과 동일하다")
    void value_roundTripsWithInstantField() {
        // given
        String key = "mopl:test:round-trip";
        Instant now = Instant.now();
        SampleValue original = new SampleValue("watcher-1", now);

        // when
        redisTemplate.opsForValue().set(key, original);
        Object restored = redisTemplate.opsForValue().get(key);

        // then
        assertThat(restored)
            .isInstanceOf(SampleValue.class)
            .isEqualTo(original);
    }

    @Test
    @DisplayName("Hash 저장 시 필드 이름도 평문으로 저장된다")
    void hash_fieldNameIsStoredAsPlainString() {
        // given
        String key = "mopl:test:hash";
        redisTemplate.opsForHash().put(key, "watcherId", "watcher-1");

        // when
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);

        // then
        // StringRedisTemplate으로 조회했을 때 필드 이름이 그대로 보여야 hashKeySerializer가 문자열로 설정된 것
        assertThat(entries).containsKey("watcherId");
    }

    @Test
    @DisplayName("StringRedisTemplate으로 카운터를 증가시킬 수 있다")
    void counter_incrementsWithStringRedisTemplate() {
        // given
        String key = "mopl:test:increment";
        stringRedisTemplate.opsForValue().set(key, "0");

        // when
        Long result = stringRedisTemplate.opsForValue().increment(key);

        // then
        assertThat(result).isEqualTo(1L);
    }

    @Test
    @DisplayName("StringRedisTemplate으로 카운터를 감소시킬 수 있다")
    void counter_decrementsWithStringRedisTemplate() {
        // given
        String key = "mopl:test:counter-decrement";
        stringRedisTemplate.opsForValue().set(key, "5");

        // when
        Long result = stringRedisTemplate.opsForValue().decrement(key);

        // then
        assertThat(result).isEqualTo(4L);
    }


    @Test
    @DisplayName("TTL을 설정하면 만료 시간이 반영된다")
    void ttl_isAppliedToKey() {
        // given
        String key = "mopl:test:ttl";
        redisTemplate.opsForValue().set(key, "value");

        // when
        redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        Long ttl = redisTemplate.getExpire(key);

        // then
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("TTL을 설정하지 않은 키는 getExpire가 -1을 반환한다")
    void ttl_isMinusOneWhenNotSet() {
        // given
        String key = "mopl:test:no-ttl";
        redisTemplate.opsForValue().set(key, "value");

        // when
        Long ttl = redisTemplate.getExpire(key);

        // then
        // Redis TTL 관례: -1 = 키는 있지만 만료시간 없음, -2 = 키 자체가 없음.
        assertThat(ttl).isEqualTo(-1L);
    }

    @Test
    @DisplayName("애플리케이션 공용 ObjectMapper는 다형성 타입 정보를 기록하지 않는다")
    void applicationObjectMapper_isNotMutatedByRedisConfig() throws Exception {
        // given
        SampleValue value = new SampleValue("watcher-1", Instant.now());

        // when
        String json = objectMapper.writeValueAsString(value);

        // then
        // RedisConfig가 objectMapper.copy()가 아니라 원본에 activateDefaultTyping()을 호출했다면 @class 필드가 섞여 나옴
        assertThat(json).doesNotContain("@class");
    }

    @Test
    @DisplayName("존재하지 않는 키를 조회하면 예외 없이 null을 반환한다")
    void missingKey_returnsNullWithoutException() {
        // when
        Object result = redisTemplate.opsForValue().get("mopl:test:does-not-exist");

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("List에 Instant를 포함한 값을 여러 개 저장하고 순서대로 조회할 수 있다")
    void list_roundTripsMultipleValuesInOrder() {
        // given
        String key = "mopl:test:list";
        SampleValue first = new SampleValue("watcher-1", Instant.parse("2026-08-11T00:00:00Z"));
        SampleValue second = new SampleValue("watcher-2", Instant.parse("2026-08-11T00:01:00Z"));

        // when
        redisTemplate.opsForList().rightPush(key, first);
        redisTemplate.opsForList().rightPush(key, second);
        java.util.List<Object> stored = redisTemplate.opsForList().range(key, 0, -1);

        // then
        assertThat(stored)
            .hasSize(2)
            .containsExactly(first, second);
    }
}
