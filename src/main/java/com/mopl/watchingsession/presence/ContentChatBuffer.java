package com.mopl.watchingsession.presence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.watchingsession.config.WatchingSessionProperties;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 콘텐츠별 최근 채팅 메시지를 Redis 리스트에 유지하는 롤링 버퍼입니다.
 *
 * 실패를 호출부로 던지지 않습니다. 버퍼는 입장 시 맥락을 조금 보여주는 부가 기능이고,
 * 원본은 이미 구독자에게 나간 브로드캐스트입니다. Redis가 흔들렸다는 이유로 채팅 전송이 발신자에게 ERROR로 되돌아가면 안 됩니다.
 *
 * 값은 타입 정보 없이 JSON 문자열로 저장합니다. {@code RedisTemplate<String, Object>}는
 * 값에 @class를 함께 적어, 클래스를 옮기는 것만으로 이미 쌓인 버퍼를 읽지 못하게 됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentChatBuffer {

    private static final String KEY_TEMPLATE = "mopl:chat:buffer:%s";

    // 추가·상한 유지·TTL 재설정을 한 스크립트로 묶는다.
    // 세 명령을 따로 보내면 그 사이에 다른 인스턴스의 RPUSH가 끼어들어 상한을 넘긴 버퍼가 보이거나
    // PEXPIRE 직전에 연결이 끊겨 TTL 없는 키가 영구히 남는다.
    private static final String APPEND_LUA = """
        redis.call('RPUSH', KEYS[1], ARGV[1])
        redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
        redis.call('PEXPIRE', KEYS[1], ARGV[3])
        return 1
        """;

    private static final RedisScript<Long> APPEND_SCRIPT =
        new DefaultRedisScript<>(APPEND_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final WatchingSessionProperties watchingSessionProperties;

    /** 브로드캐스트를 마친 메시지를 버퍼 끝에 기록합니다. 실패는 로그만 남깁니다. */
    public void append(UUID contentId, ContentChatDto message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // DTO의 직렬화 실패는 설정 오류를 뜻하므로 조용히 넘기지 않는다.
            log.error("채팅 버퍼 직렬화 실패, 기록을 건너뜁니다. contentId={}", contentId, e);
            return;
        }

        try {
            stringRedisTemplate.execute(APPEND_SCRIPT, List.of(key(contentId)),
                payload,
                String.valueOf(watchingSessionProperties.getChatBufferSize()),
                String.valueOf(watchingSessionProperties.getChatBufferTtl().toMillis()));
        } catch (RuntimeException e) {
            log.warn("채팅 버퍼 기록 실패: contentId={}", contentId, e);
        }
    }

    /** @return 오래된 순서의 최근 메시지. 버퍼가 비었거나 조회가 실패하면 빈 리스트. */
    public List<ContentChatDto> recent(UUID contentId) {
        List<String> raw;
        try {
            // 상한을 줄여 배포한 직후에는 이전 상한만큼 쌓인 키가 남아 있으므로
            // 조회에서도 -size로 끊어 현재 설정보다 많이 나가지 않게 한다.
            raw = stringRedisTemplate.opsForList().range(key(contentId),
                -watchingSessionProperties.getChatBufferSize(), -1);
        } catch (RuntimeException e) {
            log.warn("채팅 버퍼 조회 실패, 백로그 없이 진행합니다. contentId={}", contentId, e);
            return List.of();
        }

        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        List<ContentChatDto> messages = new ArrayList<>(raw.size());
        for (String each : raw) {
            try {
                messages.add(objectMapper.readValue(each, ContentChatDto.class));
            } catch (JsonProcessingException e) {
                // 예외 객체를 넘기지 않는다. Jackson 메시지에 원본 조각(사용자가 보낸 채팅 내용)이 실려 로그로 새어나간다.
                // 한 건이 깨져도 나머지는 그대로 전달한다.
                log.warn("채팅 버퍼 항목을 읽지 못해 건너뜁니다. contentId={}, reason={}",
                    contentId, e.getClass().getSimpleName());
            }
        }
        return messages;
    }

    private String key(UUID contentId) {
        return KEY_TEMPLATE.formatted(contentId);
    }
}

