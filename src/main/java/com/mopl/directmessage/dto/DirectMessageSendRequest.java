package com.mopl.directmessage.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record DirectMessageSendRequest(
    UUID clientMessageId,

    @NotBlank(message = "메시지 내용이 없습니다.")
    String content
) {
   public DirectMessageSendRequest(
       String content
   ) {
       this (
           null,
           content
       );
   }
}
