package com.mopl.notification.kafka;

import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationUserReaderImpl implements NotificationUserReader {

    private final UserRepository userRepository;

    @Override
    public Optional<String> findName(UUID userId) {
        return userRepository.findById(userId)
            .map(User::getName);
    }

    @Override
    public boolean exists(UUID userId) {
        return userRepository.existsById(userId);
    }
}
