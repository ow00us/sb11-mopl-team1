package com.mopl.notification.kafka;

import com.mopl.global.event.EventContractViolationException;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class NotificationUserReaderImpl implements NotificationUserReader {

    private final UserRepository userRepository;

    @Override
    public String getName(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() ->
                new EventContractViolationException(
                    "알림 대상자를 찾을 수 없습니다."
                )
            );
        return user.getName();
    }
}
