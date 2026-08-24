package com.mopl.directmessage.repository;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DirectMessageSequenceGenerator {

    private final JdbcTemplate jdbcTemplate;

    public long next(UUID conversationId) {
        Long messageSequence =
            jdbcTemplate.query(
                """
                UPDATE conversations
                SET next_message_sequence =
                    next_message_sequence + 1
                WHERE id = ?
                RETURNING next_message_sequence
                """,
                resultSet ->
                    resultSet.next()
                        ? resultSet.getLong(
                            "next_message_sequence"
                        )
                        : null,
                conversationId
            );

        if (messageSequence == null) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "메시지 순번을 할당할 대화를 찾을 수 없습니다."
            );
        }

        return messageSequence;
    }
}
