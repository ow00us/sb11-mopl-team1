package com.mopl.content.external.tmdb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TmdbClientConfig {

    @Bean
    public RestClient tmdbRestClient(TmdbProperties properties, ClientHttpRequestFactory externalApiRequestFactory) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(externalApiRequestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}