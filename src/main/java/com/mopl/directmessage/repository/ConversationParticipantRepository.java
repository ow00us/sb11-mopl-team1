package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.ConversationParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ConversationParticipantRepository
    extends JpaRepository<ConversationParticipant, UUID> {

    List<ConversationParticipant> findAllByConversationId(UUID conversationId);

    @Query("""
        SELECT participant.conversationId
        FROM ConversationParticipant participant
        WHERE participant.userId = :requesterId
            AND EXISTS (
                SELECT other.id
                FROM ConversationParticipant other
                WHERE other.conversationId = participant.conversationId
                    And other.userId = :withUserId
      )
    """)

    List<UUID> findConversationIdsByUserPair(
        @Param("requesterId") UUID requesterId,
        @Param("withUserId") UUID withUserId
    );
}
