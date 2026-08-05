package com.mopl.watchingsession.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContentChatSendRequest(
    @NotBlank(message = "채팅 내용은 필수입니다.")
    @Size(max = 500, message = "채팅 내용은 500자 이하로 작성 가능합니다.")
    String content
) {
}
