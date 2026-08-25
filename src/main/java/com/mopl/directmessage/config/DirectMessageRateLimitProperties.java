package com.mopl.directmessage.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "mopl.direct-message.rate-limit"
)
public class DirectMessageRateLimitProperties {

    @NotNull
    @Positive
    private Integer maxMessages;

    @NotNull
    @DurationMin(millis = 1)
    private Duration window;
}
