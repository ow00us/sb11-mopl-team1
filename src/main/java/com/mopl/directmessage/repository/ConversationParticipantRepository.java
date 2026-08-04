package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.ConversationParticipant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
                    AND other.userId = :withUserId
      )
    """)

    List<UUID> findConversationIdsByUserPair(
        @Param("requesterId") UUID requesterId,
        @Param("withUserId") UUID withUserId
    );

    @Query("""
        SELECT
            conversation.id AS conversationId,
            conversation.createdAt AS createdAt,
            other.userId AS withUserId
        FROM Conversation conversation,
             ConversationParticipant requester,
             ConversationParticipant other
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND (
                CAST(:keywordLike AS string) IS NULL
                OR EXISTS (
                    SELECT withUser.id
                    FROM User withUser
                    WHERE withUser.id = other.userId
                        AND LOWER(withUser.name) LIKE
                            LOWER(
                             CONCAT(
                                '%',
                                CONCAT(:keywordLike, '%')
                        )
                    ) ESCAPE '!'
                )
        )
        ORDER BY conversation.createdAt ASC, conversation.id ASC
        """)
    List<ConversationListItemProjection> findFirstConversationListAsc(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike,
        Pageable pageable
    );

    @Query("""
        SELECT
            conversation.id AS conversationId,
            conversation.createdAt AS createdAt,
            other.userId AS withUserId
        FROM Conversation conversation,
             ConversationParticipant requester,
             ConversationParticipant other
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND (
                CAST(:keywordLike AS string) IS NULL
                OR EXISTS (
                    SELECT withUser.id
                    FROM User withUser
                    WHERE withUser.id = other.userId
                        AND LOWER(withUser.name) LIKE
                            LOWER(
                             CONCAT(
                                '%',
                                CONCAT(:keywordLike, '%')
                        )
                    ) ESCAPE '!'
                )
        )
        ORDER BY conversation.createdAt ASC, conversation.id ASC
        """)
    List<ConversationListItemProjection> findConversationListAsc(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    @Query("""
        SELECT
            conversation.id AS conversationId,
            conversation.createdAt AS createdAt,
            other.userId AS withUserId
        FROM Conversation conversation,
             ConversationParticipant requester,
             ConversationParticipant other
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND (
                CAST(:keywordLike AS string) IS NULL
                OR EXISTS (
                    SELECT withUser.id
                    FROM User withUser
                    WHERE withUser.id = other.userId
                        AND LOWER(withUser.name) LIKE
                            LOWER(
                             CONCAT(
                                '%',
                                CONCAT(:keywordLike, '%')
                        )
                    ) ESCAPE '!'
                )
        )
        ORDER BY conversation.createdAt DESC, conversation.id DESC
        """)
    List<ConversationListItemProjection> findFirstConversationListDesc(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike,
        Pageable pageable
    );

    @Query("""
        SELECT
            conversation.id AS conversationId,
            conversation.createdAt AS createdAt,
            other.userId AS withUserId
        FROM Conversation conversation,
             ConversationParticipant requester,
             ConversationParticipant other
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND (
                CAST(:keywordLike AS string) IS NULL
                OR EXISTS (
                    SELECT withUser.id
                    FROM User withUser
                    WHERE withUser.id = other.userId
                        AND LOWER(withUser.name) LIKE
                            LOWER(
                             CONCAT(
                                '%',
                                CONCAT(:keywordLike, '%')
                        )
                    ) ESCAPE '!'
                )
        )
            AND (
                conversation.createdAt < :cursor
                OR (
                    conversation.createdAt = :cursor
                    AND conversation.id < :idAfter
                )
            )
        ORDER BY conversation.createdAt DESC, conversation.id DESC
        """)
    List<ConversationListItemProjection> findConversationListDesc(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    @Query("""
        SELECT COUNT(DISTINCT conversation.id)
        FROM Conversation conversation,
             ConversationParticipant requester,
             ConversationParticipant other
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND (
                CAST(:keywordLike AS string) IS NULL
                OR EXISTS (
                    SELECT withUser.id
                    FROM User withUser
                    WHERE withUser.id = other.userId
                        AND LOWER(withUser.name) LIKE
                            LOWER(
                             CONCAT(
                                '%',
                                CONCAT(:keywordLike, '%')
                        )
                    ) ESCAPE '!'
                )
        )
        """)
    long countConversationList(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike
    );
}
