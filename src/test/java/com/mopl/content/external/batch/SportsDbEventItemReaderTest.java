package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.SportsDbApiException;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SportsDbEventItemReaderTest {

    private final SportsDbApiClient sportsDbApiClient = mock(SportsDbApiClient.class);

    @Test
    @DisplayName("날짜를 바깥 루프로, 리그를 안쪽 루프로 순회하며 이벤트를 반환한다")
    void read_iteratesDatesOuterAndLeaguesInner() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 8, 1);
        LocalDate day2 = LocalDate.of(2026, 8, 2);
        // SportsDbEventSummary는 record(final)라 기본 subclass mock maker로는 mock()할 수 없어 실제 인스턴스를 사용한다.
        SportsDbEventSummary eventA = new SportsDbEventSummary(
                "1", "Event A", "League A", "Soccer", "2026-08-01", "https://thumb-a.jpg", "filename-a");
        SportsDbEventSummary eventB = new SportsDbEventSummary(
                "2", "Event B", "League B", "Soccer", "2026-08-02", "https://thumb-b.jpg", "filename-b");

        when(sportsDbApiClient.getEventsByDay(day1, 100)).thenReturn(List.of(eventA));
        when(sportsDbApiClient.getEventsByDay(day1, 200)).thenReturn(List.of());
        when(sportsDbApiClient.getEventsByDay(day2, 100)).thenReturn(List.of());
        when(sportsDbApiClient.getEventsByDay(day2, 200)).thenReturn(List.of(eventB));

        SportsDbEventItemReader reader = new SportsDbEventItemReader(
                sportsDbApiClient, List.of(100, 200), List.of(day1, day2));

        assertThat(reader.read()).isEqualTo(eventA);
        assertThat(reader.read()).isEqualTo(eventB);
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("특정 (날짜, 리그) 조회가 실패하면 예외를 던지지 않고 다음 조합으로 넘어간다")
    void read_oneComboFails_logsAndContinuesToNextCombo() throws Exception {
        LocalDate day1 = LocalDate.of(2026, 8, 1);
        SportsDbEventSummary event = new SportsDbEventSummary(
                "1", "Event A", "League A", "Soccer", "2026-08-01", "https://thumb-a.jpg", "filename-a");

        when(sportsDbApiClient.getEventsByDay(day1, 100)).thenThrow(new SportsDbApiException("일시적 장애", null));
        when(sportsDbApiClient.getEventsByDay(day1, 200)).thenReturn(List.of(event));

        SportsDbEventItemReader reader = new SportsDbEventItemReader(
                sportsDbApiClient, List.of(100, 200), List.of(day1));

        assertThat(reader.read()).isEqualTo(event);
        assertThat(reader.read()).isNull();
    }
}