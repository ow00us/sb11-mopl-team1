package com.mopl.directmessage.dto;

import jakarta.validation.constraints.NotBlank;

public record DirectMessageSendRequest(
    @NotBlank(message = "메시지 내용이 없습니다.")
    String content
) {
}
