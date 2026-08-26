package com.mopl.notification.kafka;

import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import com.mopl.notification.service.NotificationService;
import com.mopl.global.event.EventContractViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationKafkaListener {

    private static final String CONSUMER_GROUP = "mopl.notification";

    private final NotificationEventMapper notificationEventMapper;
    private final NotificationService notificationService;

    @KafkaListener(topics = {
        MoplTopics.FOLLOW_EVENTS,
        MoplTopics.PLAYLIST_EVENTS,
        MoplTopics.DIRECT_MESSAGE_EVENTS
    },
    groupId = CONSUMER_GROUP,
    containerFactory = "eventKafkaListenerContainerFactory"
    )
    public void consume(EventEnvelope envelope) {
        if (envelope == null || !StringUtils.hasText(envelope.type())) {
            throw new EventContractViolationException(
                "이벤트 type이 없습니다."
            );
        }

        if (!notificationEventMapper.supports(envelope.type())) {
            return;
        }
        if (!notificationEventMapper.supports(envelope.type(), envelope.version())) {
            throw new EventContractViolationException(
                "지원하지 않는 알림 이벤트 type·version입니다."
            );
        }

        notificationEventMapper.map(envelope)
            .ifPresent(
                notificationService::createIfAbsent
            );
    }
}
