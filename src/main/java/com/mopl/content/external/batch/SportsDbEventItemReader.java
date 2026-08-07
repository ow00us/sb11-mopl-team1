package com.mopl.content.external.batch;

import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemReader;

/**
 * (날짜 × 리그) 조합을 순회하며 Sports DB 이벤트를 읽는다. 날짜를 바깥 루프,
 * 리그를 안쪽 루프로 순회한다. Job 실행마다 상태가 초기화되어야 하므로 @StepScope 빈으로 등록한다.
 */
@RequiredArgsConstructor
public class SportsDbEventItemReader implements ItemReader<SportsDbEventSummary> {

    private final SportsDbApiClient sportsDbApiClient;
    private final List<Integer> leagueIds;
    private final List<LocalDate> dates;

    private int dateIndex = 0;
    private int leagueIndex = 0;
    private Iterator<SportsDbEventSummary> currentIterator = Collections.emptyIterator();

    @Override
    public SportsDbEventSummary read() {
        while (!currentIterator.hasNext()) {
            if (dateIndex >= dates.size()) {
                return null;
            }
            LocalDate date = dates.get(dateIndex);
            int leagueId = leagueIds.get(leagueIndex);
            currentIterator = sportsDbApiClient.getEventsByDay(date, leagueId).iterator();

            leagueIndex++;
            if (leagueIndex >= leagueIds.size()) {
                leagueIndex = 0;
                dateIndex++;
            }
        }
        return currentIterator.next();
    }
}