package com.mopl.content.external.sportsdb;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sportsdb")
public record SportsDbProperties(String baseUrl, String apiKey, List<Integer> leagueIds) {
}