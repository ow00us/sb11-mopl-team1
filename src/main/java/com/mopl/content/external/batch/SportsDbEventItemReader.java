package com.mopl.content.external.batch;

import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.SportsDbApiException;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;

/**
 * (날짜 × 리그) 조합을 순회하며 Sports DB 이벤트를 읽는다. 날짜를 바깥 루프,
 * 리그를 안쪽 루프로 순회한다. 특정 (날짜, 리그) 조회가 실패하면 로그를 남기고 Micrometer
 * 카운터를 증가시킨 뒤 다음 조합으로 넘어간다 — 커서를 API 호출 전에 미리 전진시켜서,
 * 같은 조합을 반복 요청하다 skipLimit을 소진하는 것을 방지한다.
 * Job 실행마다 상태가 초기화되어야 하므로 @StepScope 빈으로 등록한다.
 */
@Slf4j
@RequiredArgsConstructor
public class SportsDbEventItemReader implements ItemReader<SportsDbEventSummary> {

    private static final String FAILURE_METRIC_NAME = "external-content-batch.api.failures";

    private final SportsDbApiClient sportsDbApiClient;
    private final List<Integer> leagueIds;
    private final List<LocalDate> dates;
    private final MeterRegistry meterRegistry;

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

            leagueIndex++;
            if (leagueIndex >= leagueIds.size()) {
                leagueIndex = 0;
                dateIndex++;
            }

            try {
                currentIterator = sportsDbApiClient.getEventsByDay(date, leagueId).iterator();
            } catch (SportsDbApiException e) {
                log.warn("Sports DB {} 리그 {} 날짜 이벤트 조회 실패, 다음 조합으로 넘어갑니다.", leagueId, date, e);
                meterRegistry.counter(FAILURE_METRIC_NAME, "source", "sportsdb").increment();
                currentIterator = Collections.emptyIterator();
            }
        }
        return currentIterator.next();
    }
}