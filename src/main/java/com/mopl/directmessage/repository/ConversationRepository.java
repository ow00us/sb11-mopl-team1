package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.Conversation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository
    extends JpaRepository<Conversation, UUID> {

    Optional<Conversation>
        findByParticipantPairKey(
            String participantPairKey
        );

    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query(
        value = """
            INSERT INTO conversations (
                id,
                created_at,
                updated_at,
                participant_pair_key
            )
            VALUES (
                :conversationId,
                :createdAt,
                :createdAt,
                :participantPairKey
            )
            ON CONFLICT ON CONSTRAINT
                uk_conversations_participant_pair_key
            DO NOTHING
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("conversationId")
        UUID conversationId,

        @Param("createdAt")
        Instant createdAt,

        @Param("participantPairKey")
        String participantPairKey
    );
}
