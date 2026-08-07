package com.mopl.content.external.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external-content-batch")
public record ExternalContentBatchProperties(
        int tmdbMaxPages,
        int sportsDbPastDays,
        int sportsDbFutureDays
) {
}