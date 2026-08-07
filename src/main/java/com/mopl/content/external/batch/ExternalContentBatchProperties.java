package com.mopl.content.external.batch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "external-content-batch")
public record ExternalContentBatchProperties(
        int tmdbMaxPages,
        int sportsDbPastDays,
        int sportsDbFutureDays
) {

    public ExternalContentBatchProperties {
        if (tmdbMaxPages < 1) {
            throw new IllegalStateException(
                    "external-content-batch.tmdb-max-pages must be at least 1: " + tmdbMaxPages);
        }
        if (sportsDbPastDays < 0) {
            throw new IllegalStateException(
                    "external-content-batch.sportsdb-past-days must not be negative: " + sportsDbPastDays);
        }
        if (sportsDbFutureDays < 0) {
            throw new IllegalStateException(
                    "external-content-batch.sportsdb-future-days must not be negative: " + sportsDbFutureDays);
        }
    }
}