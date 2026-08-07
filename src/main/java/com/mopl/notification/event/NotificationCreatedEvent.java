package com.mopl.notification.event;

import com.mopl.notification.dto.NotificationDto;

//알림 생성 이벤트 생성
public record NotificationCreatedEvent(
    NotificationDto notification
) {
}
