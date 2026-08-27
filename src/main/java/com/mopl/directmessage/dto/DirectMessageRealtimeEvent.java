package com.mopl.directmessage.dto;

public record DirectMessageRealtimeEvent<T>(
    DirectMessageRealtimeEventType type,
    T data
) {

    public static DirectMessageRealtimeEvent<DirectMessageDto> created(
        DirectMessageDto directMessage
    ) {
        return new DirectMessageRealtimeEvent<>(
            DirectMessageRealtimeEventType
                .DIRECT_MESSAGE_CREATED,
            directMessage
        );
    }

    public static DirectMessageRealtimeEvent<DirectMessageReadEvent> read(
        DirectMessageReadEvent readEvent)
    {
        return new DirectMessageRealtimeEvent<>(
            DirectMessageRealtimeEventType
                .DIRECT_MESSAGE_READ,
            readEvent
        );
    }
}
