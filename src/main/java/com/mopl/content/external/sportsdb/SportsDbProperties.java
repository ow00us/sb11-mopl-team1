package com.mopl.content.external.sportsdb;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sportsdb")
public record SportsDbProperties(String baseUrl, String apiKey, List<Integer> leagueIds) {

    public SportsDbProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("sportsdb.base-url must not be null or blank.");
        }
        if (!baseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "sportsdb.base-url must use HTTPS to avoid sending the API key in cleartext: " + baseUrl);
        }
    }
}