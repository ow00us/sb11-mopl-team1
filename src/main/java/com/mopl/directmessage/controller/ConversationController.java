package com.mopl.directmessage.controller;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.service.ConversationService;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

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
}
