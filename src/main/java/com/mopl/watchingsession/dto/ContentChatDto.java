package com.mopl.watchingsession.dto;

import com.mopl.global.common.UserSummary;

public record ContentChatDto(
    UserSummary sender,
    String content
) {
}
