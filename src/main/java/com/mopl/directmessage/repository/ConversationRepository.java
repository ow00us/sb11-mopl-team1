package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationRepository
    extends JpaRepository<Conversation, UUID> {
}
