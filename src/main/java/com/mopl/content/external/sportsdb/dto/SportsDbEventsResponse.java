package com.mopl.content.external.sportsdb.dto;

import java.util.List;

public record SportsDbEventsResponse(List<SportsDbEventSummary> events) {
}