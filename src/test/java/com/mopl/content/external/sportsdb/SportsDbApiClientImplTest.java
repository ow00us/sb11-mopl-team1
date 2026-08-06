package com.mopl.content.external.sportsdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SportsDbApiClientImplTest {

    private RestClient.Builder newBuilder() {
        return RestClient.builder().baseUrl("https://www.thesportsdb.com/api/v1/json/123");
    }

    @Test
    @DisplayName("getEventsByDay는 날짜·리그 ID로 요청하고 정상 응답을 파싱한다")
    void getEventsByDay_success_parsesResponse() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SportsDbApiClientImpl client = new SportsDbApiClientImpl(builder.build());

        String body = """
                {
                  "events": [
                    {"idEvent": "1", "strEvent": "Chelsea vs Juventus", "strLeague": "Club Friendlies",
                     "strSport": "Soccer", "dateEvent": "2026-08-05", "strThumb": "https://thumb.jpg",
                     "strFilename": "Club Friendlies 2026-08-05 Chelsea vs Juventus"}
                  ]
                }
                """;

        server.expect(requestTo("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-08-05&l=4569"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SportsDbEventSummary> events = client.getEventsByDay(LocalDate.of(2026, 8, 5), 4569);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventName()).isEqualTo("Chelsea vs Juventus");
        server.verify();
    }

    @Test
    @DisplayName("getEventsByDay는 events가 null인 응답을 빈 리스트로 처리한다")
    void getEventsByDay_nullEvents_returnsEmptyList() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SportsDbApiClientImpl client = new SportsDbApiClientImpl(builder.build());

        server.expect(requestTo("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-08-05&l=4569"))
                .andRespond(withSuccess("{\"events\": null}", MediaType.APPLICATION_JSON));

        List<SportsDbEventSummary> events = client.getEventsByDay(LocalDate.of(2026, 8, 5), 4569);

        assertThat(events).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("getEventsByDay는 5xx 응답을 SportsDbApiException으로 변환한다")
    void getEventsByDay_serverError_throwsSportsDbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SportsDbApiClientImpl client = new SportsDbApiClientImpl(builder.build());

        server.expect(requestTo("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-08-05&l=4569"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getEventsByDay(LocalDate.of(2026, 8, 5), 4569))
                .isInstanceOf(SportsDbApiException.class);
        server.verify();
    }

    @Test
    @DisplayName("getEventsByDay는 429(rate limit) 응답을 SportsDbApiException으로 변환한다")
    void getEventsByDay_rateLimited_throwsSportsDbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SportsDbApiClientImpl client = new SportsDbApiClientImpl(builder.build());

        server.expect(requestTo("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-08-05&l=4569"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getEventsByDay(LocalDate.of(2026, 8, 5), 4569))
                .isInstanceOf(SportsDbApiException.class);
        server.verify();
    }

    @Test
    @DisplayName("getEventsByDay는 통신 실패(I/O 예외)를 SportsDbApiException으로 변환한다")
    void getEventsByDay_ioException_throwsSportsDbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SportsDbApiClientImpl client = new SportsDbApiClientImpl(builder.build());

        server.expect(requestTo("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=2026-08-05&l=4569"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.getEventsByDay(LocalDate.of(2026, 8, 5), 4569))
                .isInstanceOf(SportsDbApiException.class);
        server.verify();
    }
}