package com.mopl.notification.kafka;

import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import com.mopl.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
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
        NotificationCreateCommand command = notificationEventMapper.map(envelope);
        notificationService.createIfAbsent(command);
    }
}
