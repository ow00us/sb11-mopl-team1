package com.mopl.content.external.sportsdb;

import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import com.mopl.content.external.sportsdb.dto.SportsDbEventsResponse;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class SportsDbApiClientImpl implements SportsDbApiClient {

    private final RestClient sportsDbRestClient;

    @Override
    public List<SportsDbEventSummary> getEventsByDay(LocalDate date, int leagueId) {
        try {
            SportsDbEventsResponse response = sportsDbRestClient.get()
                    .uri("/eventsday.php?d={date}&l={leagueId}", date.toString(), leagueId)
                    .retrieve()
                    .body(SportsDbEventsResponse.class);
            List<SportsDbEventSummary> events = response == null || response.events() == null
                    ? List.of()
                    : response.events();
            log.info("Sports DB 이벤트 조회 완료 (date={}, leagueId={}, count={})", date, leagueId, events.size());
            return events;
        } catch (RestClientException e) {
            throw new SportsDbApiException("Sports DB 이벤트 조회 실패 (date=" + date + ", leagueId=" + leagueId + ")", e);
        }
    }
}