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
             ConversationParticipant other,
             User withUser
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND withUser.id = other.userId
            AND (
                :keywordLike IS NULL
                OR LOWER(withUser.name) LIKE
                    LOWER(CONCAT('%', CONCAT(:keywordLike, '%')))
            )
            AND (
                :cursor IS NULL
                OR conversation.createdAt > :cursor
                OR (
                    conversation.createdAt = :cursor
                    AND conversation.id > :idAfter
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
             ConversationParticipant other,
             User withUser
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND withUser.id = other.userId
            AND (
                :keywordLike IS NULL
                OR LOWER(withUser.name) LIKE
                    LOWER(CONCAT('%', CONCAT(:keywordLike, '%')))
            )
            AND (
                :cursor IS NULL
                OR conversation.createdAt < :cursor
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
             ConversationParticipant other,
             User withUser
        WHERE requester.conversationId = conversation.id
            AND requester.userId = :requesterId
            AND other.conversationId = conversation.id
            AND other.userId <> :requesterId
            AND withUser.id = other.userId
            AND (
                :keywordLike IS NULL
                OR LOWER(withUser.name) LIKE
                    LOWER(CONCAT('%', CONCAT(:keywordLike, '%')))
            )
        """)
    long countConversationList(
        @Param("requesterId") UUID requesterId,
        @Param("keywordLike") String keywordLike
    );
}
