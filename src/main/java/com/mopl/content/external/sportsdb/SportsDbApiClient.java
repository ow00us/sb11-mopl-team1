package com.mopl.content.external.sportsdb;

import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import java.time.LocalDate;
import java.util.List;

public interface SportsDbApiClient {

    List<SportsDbEventSummary> getEventsByDay(LocalDate date, int leagueId);
}