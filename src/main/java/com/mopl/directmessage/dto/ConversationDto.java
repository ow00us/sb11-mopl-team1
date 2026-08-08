package com.mopl.directmessage.dto;

import com.mopl.global.common.UserSummary;
import java.util.UUID;

public record ConversationDto(
    UUID id,
    UserSummary with,
    DirectMessageDto latestMessage,
    boolean hasUnread
) {
}
