package com.mopl.directmessage.controller;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.service.ConversationService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.security.Principal;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public CursorResponse<ConversationDto> getConversations(
        @RequestParam(required = false)
        String keywordLike,

        @RequestParam(required = false)
        String cursor,

        @RequestParam(required = false)
        UUID idAfter,

        @RequestParam
        @Min(1)
        @Max(100)
        int limit,

        @RequestParam
        @Pattern(
            regexp = "ASCENDING|DESCENDING",
            message =
                "sortDirection은 오름차순 또는 "
                    + "내림차순이어야 합니다."
        )
        String sortDirection,

        @RequestParam
        @Pattern(
            regexp = "createdAt",
            message =
                "sortBy는 생성일자만 허용됩니다."
        )
        String sortBy,

        Principal principal
    ) {
        UUID requesterId =
            getRequesterId(principal);

        return conversationService.getConversations(
            requesterId,
            keywordLike,
            cursor,
            idAfter,
            limit,
            sortDirection,
            sortBy
        );
    }

    @PostMapping
    public ResponseEntity<ConversationDto> create(
        @Valid @RequestBody
        ConversationCreateRequest request,
        Principal principal
    ) {
        UUID requesterId = getRequesterId(principal);

        ConversationCreateResult result =
            conversationService.create(
                requesterId,
                request
            );

        HttpStatus status =
            result.created()
            ? HttpStatus.CREATED
            : HttpStatus.OK;

        return ResponseEntity
            .status(status)
            .body(result.conversation());
    }

    private UUID getRequesterId(Principal principal) {
        if (principal == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        try {
            return UUID.fromString(
                principal.getName()
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }
    }

    @GetMapping("/{conversationId}")
    public ConversationDto getConversation(
        @PathVariable UUID conversationId,
        Principal principal
    ) {
        UUID requesterId = getRequesterId(principal);

        return conversationService.getConversation(
            requesterId,
            conversationId
        );
    }

    @GetMapping("/with")
    public ConversationDto getConversationWithUser(
        @RequestParam UUID userId,
        Principal principal
    ) {
        UUID requesterId = getRequesterId(principal);

        return conversationService.getConversationWithUser(
            requesterId,
            userId
        );
    }
}
