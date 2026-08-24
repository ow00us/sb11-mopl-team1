package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.Conversation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository
    extends JpaRepository<Conversation, UUID> {

    Optional<Conversation>
        findByParticipantPairKey(
            String participantPairKey
        );
}
