package com.mopl.content.external.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tmdb")
public record TmdbProperties(String baseUrl, String accessToken) {
}
