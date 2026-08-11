package com.mopl.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 공통 직렬화 설정입니다.
 *
 * 값 직렬화 대상(presence, 채팅 롤링 버퍼, Pub/Sub 페이로드 등)은 이 RedisTemplate을 주입받아 사용합니다.
 * 순수 카운터(INCR/DECR)가 필요한 곳은 이 빈 대신 Spring Boot가 자동 등록하는 StringRedisTemplate을
 * 그대로 사용합니다
 *
 * RedisConnectionFactory는 별도로 정의하지 않고 Spring Boot 오토컨피그(LettuceConnectionFactory)를
 * 그대로 사용합니다. 직접 재정의하면 SPRING_DATA_REDIS_HOST/PORT 환경 변수 계약과
 * Actuator RedisHealthIndicator가 오토컨피그 빈을 전제로 연결되어 있어 깨질 수 있습니다.
 */
@Configuration
public class RedisConfig {

    /**
     * 키는 사람이 읽을 수 있는 평문 문자열로, 값은 타입 정보를 포함한 JSON으로 저장합니다.
     *
     * 값 직렬화기에 애플리케이션 공용 ObjectMapper를 그대로 넘기지 않고 복사본을 사용합니다.
     * 공용 ObjectMapper는 REST 오류 응답(SecurityErrorResponseWriter)과 STOMP ERROR 프레임
     * (StompErrorFrameSender)이 함께 사용하므로, 여기서 다형성 타입 정보(@class 필드) 기록을
     * 켜면 그 응답들에도 영향이 갈 수 있습니다. 복사본을 사용하면 JavaTimeModule 등 기존 Jackting
     * 설정은 그대로 물려받으면서 원본 빈은 건드리지 않습니다.
     *
     * @param objectMapper Spring Boot가 자동 등록한 애플리케이션 공용 ObjectMapper (복사해서만 사용)
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
        RedisConnectionFactory redisConnectionFactory,
        ObjectMapper objectMapper
    ) {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        // GenericJackson2JsonRedisSerializer가 타입 정보를 복원하려면 다형성 타입 기록이 켜져있어야 함
        // 외부 입력이 아닌 우리가 직접 저장한 신뢰된 값만 역직렬화하는 용도라 허용 목록 검증 필요 없음
        redisObjectMapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            DefaultTyping.EVERYTHING,
            As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer valueSerializer =
            new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        StringRedisSerializer keySerializer = new StringRedisSerializer();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(keySerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();

        return template;
    }

}
