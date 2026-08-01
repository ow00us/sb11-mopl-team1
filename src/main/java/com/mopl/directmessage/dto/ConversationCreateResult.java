package com.mopl.directmessage.dto;

public record ConversationCreateResult(
    ConversationDto conversation,
    boolean created
) {
}
