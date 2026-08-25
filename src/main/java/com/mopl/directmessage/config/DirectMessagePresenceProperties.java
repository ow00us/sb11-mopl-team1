package com.mopl.directmessage.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "mopl.direct-message.presence")
public class DirectMessagePresenceProperties {

    @NotNull
    private Duration ttl;

    @NotNull
    private Duration renewInterval;
}
