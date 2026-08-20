package com.mopl.playlist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@code @DataJpaTest} 는 {@code JacksonAutoConfiguration} 을 로드하지 않으므로
 * {@link com.mopl.playlist.event.PlaylistSubscriptionEventFactory} 가 요구하는 {@link ObjectMapper}
 * 를 통합 테스트 컨텍스트에 명시적으로 등록한다.
 *
 * <p>플레이리스트 통합 테스트 5개가 동일하게 필요하므로 공통 config 로 분리한다.
 */
@TestConfiguration
public class PlaylistIntegrationTestConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}