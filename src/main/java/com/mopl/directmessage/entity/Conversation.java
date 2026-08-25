package com.mopl.directmessage.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseEntity {

    @Column(
        name = "participant_pair_key",
        nullable = false,
        updatable = false,
        length = 73
    )
    private String participantPairKey;

    private Conversation(
        String participantPairKey
    ) {
        this.participantPairKey =
            participantPairKey;
    }

    public static Conversation create(
        UUID firstUserId,
        UUID secondUserId
    ) {
        return new Conversation(
            ConversationPairKey.create(
                firstUserId,
                secondUserId
            )
        );
    }
}
