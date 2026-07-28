package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.DirectMessage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectMessageRepository
    extends JpaRepository<DirectMessage, UUID> {
}
