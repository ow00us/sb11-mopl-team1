package com.mopl.directmessage.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation extends BaseEntity {

    public static Conversation create() {
        return new Conversation();
    }

}
