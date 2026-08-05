package com.mopl.content.external.sportsdb;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sportsdb")
public record SportsDbProperties(String baseUrl, String apiKey, List<Integer> leagueIds) {
}