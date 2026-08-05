package com.mopl.content.external.sportsdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SportsDbEventSummary(
        @JsonProperty("idEvent") String idEvent,
        @JsonProperty("strEvent") String eventName,
        @JsonProperty("strLeague") String leagueName,
        @JsonProperty("strSport") String sport,
        @JsonProperty("dateEvent") String dateEvent,
        @JsonProperty("strThumb") String thumbnail,
        @JsonProperty("strFilename") String filename
) {
}