package com.mopl.notification.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.service.NotificationService;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public CursorResponse<NotificationDto> getNotifications(
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) UUID idAfter,
        @RequestParam int limit,
        @RequestParam String sortDirection,
        @RequestParam String sortBy,
        Principal principal
    ) {
        UUID receiverId = getReceiverId(principal);

        return notificationService.getUnreadNotifications(
            receiverId,
            cursor,
            idAfter,
            limit,
            sortDirection,
            sortBy
        );
    }

    @DeleteMapping("/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(
        @PathVariable UUID notificationId,
        Principal principal
    ) {
        UUID receiverId = getReceiverId(principal);

        notificationService.read(
            notificationId,
            receiverId
        );
    }

    private UUID getReceiverId(Principal principal) {
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
}
