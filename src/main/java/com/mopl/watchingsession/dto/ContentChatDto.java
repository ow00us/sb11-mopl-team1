package com.mopl.watchingsession.dto;

import com.mopl.global.common.UserSummary;
import com.mopl.user.entity.User;

public record ContentChatDto(
    UserSummary sender,
    String content
) {
    public static ContentChatDto from(User sender, String content) {
        return new ContentChatDto(
            new UserSummary(sender.getId(), sender.getName(), sender.getProfileImageUrl()),
            content
        );
    }
}
