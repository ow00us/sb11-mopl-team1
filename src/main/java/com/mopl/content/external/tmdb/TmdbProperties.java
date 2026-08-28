package com.mopl.content.external.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tmdb")
public record TmdbProperties(String baseUrl, String accessToken, String imageBaseUrl, String language) {
}
