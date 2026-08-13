package com.mopl.global.event;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}
