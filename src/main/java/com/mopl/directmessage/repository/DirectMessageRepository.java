package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.DirectMessage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectMessageRepository
    extends JpaRepository<DirectMessage, UUID> {

    long countByConversationId(UUID conversationId);

    private static Instant normalizeToMicros(Instant cursor) {
        return cursor
            .plusNanos(500)
            .truncatedTo(ChronoUnit.MICROS);
    }

    List<DirectMessage> findAllByConversationIdOrderByCreatedAtDescIdDesc(
        UUID conversationId,
        Pageable pageable
    );

    List<DirectMessage> findAllByConversationIdOrderByCreatedAtAscIdAsc(
        UUID conversationId,
        Pageable pageable
    );

    Optional<DirectMessage> findByIdAndConversationId(
        UUID directMessageId,
        UUID conversationId
    );

    Optional<DirectMessage> findFirstByConversationIdOrderByCreatedAtDescIdDesc(
        UUID conversationId
    );

    boolean existsByConversationIdAndSenderIdNotAndReadAtIsNull(
        UUID conversationID,
        UUID senderId
    );

    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query("""
    UPDATE DirectMessage message
    SET message.readAt = :readAt,
        message.updatedAt = :readAt
    WHERE message.conversationId = :conversationId
      AND message.senderId <> :readerId
      AND message.messageSequence <= :lastReadMessageSequence
      AND message.readAt IS NULL
    """)
    int markAsReadThrough(
        @Param("conversationId") UUID conversationId,
        @Param("readerId") UUID readerId,
        @Param("lastReadMessageSequence") long lastReadMessageSequence,
        @Param("readAt") Instant readAt
    );

    @Query("""
    SELECT dm
    FROM DirectMessage dm
    WHERE dm.conversationId = :conversationId
      AND (
        dm.createdAt < :cursor
        OR (dm.createdAt = :cursor AND dm.id < :idAfter)
      )
    ORDER BY dm.createdAt DESC, dm.id DESC
    """)
    List<DirectMessage> findAllByCursorDescQuery(
        @Param("conversationId") UUID conversationId,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    @Query("""
    SELECT dm
    FROM DirectMessage dm
    WHERE dm.conversationId = :conversationId
      AND (
        dm.createdAt > :cursor
        OR (dm.createdAt = :cursor AND dm.id > :idAfter)
      )
    ORDER BY dm.createdAt ASC, dm.id ASC
    """)
    List<DirectMessage> findAllByCursorAscQuery(
        @Param("conversationId") UUID conversationId,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    default List<DirectMessage> findAllByCursorDesc(
        UUID conversationId,
        Instant cursor,
        UUID idAfter,
        Pageable pageable
    ) {
        Instant normalizedCursor = normalizeToMicros(cursor);

        return findAllByCursorDescQuery(
            conversationId,
            normalizedCursor,
            idAfter,
            pageable
        );
    }

    default List<DirectMessage> findAllByCursorAsc(
        UUID conversationId,
        Instant cursor,
        UUID idAfter,
        Pageable pageable
    ) {
        Instant normalizedCursor = normalizeToMicros(cursor);

        return findAllByCursorAscQuery(
            conversationId,
            normalizedCursor,
            idAfter,
            pageable
        );
    }

    @Query("""
    SELECT message
    FROM DirectMessage message
    WHERE message.conversationId IN :conversationIds
        AND NOT EXISTS (
            SELECT newerMessage.id
            FROM DirectMessage newerMessage
            WHERE newerMessage.conversationId =
                message.conversationId
                AND (
                    newerMessage.createdAt >
                        message.createdAt
                    OR (
                        newerMessage.createdAt =
                            message.createdAt
                        AND newerMessage.id >
                            message.id
                    )
                )
        )
    """)
    List<DirectMessage> findLatestMessagesByConversationIds(
        @Param("conversationIds")
        List<UUID> conversationIds
    );

    @Query("""
    SELECT DISTINCT message.conversationId
    FROM DirectMessage message
    WHERE message.conversationId IN :conversationIds
        AND message.senderId <> :requesterId
        AND message.readAt IS NULL
    """)
    List<UUID> findUnreadConversationIds(
        @Param("conversationIds")
        List<UUID> conversationIds,
        @Param("requesterId")
        UUID requesterId
    );

    @Query("""
        SELECT
            message.id AS id,
            message.conversationId AS conversationId,
            message.createdAt AS createdAt,
            message.messageSequence AS messageSequence,
            sender.id AS senderId,
            sender.name AS senderName,
            sender.profileImageUrl AS senderProfileImageUrl,
            receiver.id AS receiverId,
            receiver.name AS receiverName,
            receiver.profileImageUrl AS receiverProfileImageUrl,
            message.content AS content
        FROM DirectMessage message,
             ConversationParticipant participant,
             User sender,
             User receiver
        WHERE participant.conversationId =
                message.conversationId
            AND participant.userId = :receiverId
            AND message.senderId <> :receiverId
            AND sender.id = message.senderId
            AND receiver.id = :receiverId
            AND (
                message.createdAt > :cursor
                OR (
                    message.createdAt = :cursor
                    AND message.id > :idAfter
                )
            )
        ORDER BY message.createdAt ASC,
                 message.id ASC
        """)
    List<DirectMessageReplayProjection>
        findAllReceivedForReplay(
            @Param("receiverId")
            UUID receiverId,

            @Param("cursor")
            Instant cursor,

            @Param("idAfter")
            UUID idAfter,

            Pageable pageable
        );
}
