package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationParticipantRepository
    extends JpaRepository<ConversationParticipant, UUID> {

    List<ConversationParticipant> findAllByConversationId(UUID conversationId);
}
