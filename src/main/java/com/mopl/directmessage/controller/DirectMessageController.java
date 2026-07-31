package com.mopl.directmessage.controller;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.service.DirectMessageService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@RequestMapping(
    "/api/conversations/{conversationId}/direct-messages"
)
@RequiredArgsConstructor
public class DirectMessageController {

    private final DirectMessageService directMessageService;

    @GetMapping
    public CursorResponse<DirectMessageDto> getDirectMessages(
        @PathVariable UUID conversationId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) UUID idAfter,
        @RequestParam
        @Min(value = 1, message = "limit은 1 이상이어야 합니다.")
        @Max(value = 100, message = "limit은 100 이하여야 합니다.")
        int limit,
        @RequestParam String sortDirection,
        @RequestParam String sortBy,
        Principal principal
    ) {
        UUID requesterId = getRequesterId(principal);

        return directMessageService.getDirectMessages(
            requesterId,
            conversationId,
            cursor,
            idAfter,
            limit,
            sortDirection,
            sortBy
        );
    }

    private UUID getRequesterId(Principal principal) {
        if (principal == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }
    }

    @PostMapping("/{directMessageId}/read")
    public void read(
        @PathVariable UUID conversationId,
        @PathVariable UUID directMessageId,
        Principal principal
    ) {
        UUID requesterId = getRequesterId(principal);

        directMessageService.read(
            requesterId,
            conversationId,
            directMessageId
        );
    }
}
