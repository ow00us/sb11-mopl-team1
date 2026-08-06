package com.mopl.content.external.sportsdb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class SportsDbClientConfig {

    @Bean
    public RestClient sportsDbRestClient(SportsDbProperties properties, ClientHttpRequestFactory externalApiRequestFactory) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl() + "/" + properties.apiKey())
                .requestFactory(externalApiRequestFactory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}