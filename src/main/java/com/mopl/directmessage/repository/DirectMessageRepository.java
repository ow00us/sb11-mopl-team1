package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.DirectMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectMessageRepository
    extends JpaRepository<DirectMessage, UUID> {

    List<DirectMessage> findAllByConversationIdOrderByCreatedAtDescIdDesc(
        UUID conversationId,
        Pageable pageable
    );

    List<DirectMessage> findAllByConversationIdOrderByCreatedAtAscIdAsc(
        UUID conversationId,
        Pageable pageable
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
    List<DirectMessage> findAllByCursorDesc(
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
    List<DirectMessage> findAllByCursorAsc(
        @Param("conversationId") UUID conversationId,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );
}
