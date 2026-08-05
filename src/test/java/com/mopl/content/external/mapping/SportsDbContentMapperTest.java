package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SportsDbContentMapperTest {

    private final SportsDbContentMapper mapper = new SportsDbContentMapper();

    @Test
    @DisplayName("이벤트 응답의 모든 필드가 draft에 매핑된다")
    void toDraft_mapsAllFields() {
        SportsDbEventSummary event = new SportsDbEventSummary(
                "1", "Chelsea vs Juventus", "Club Friendlies", "Soccer",
                "2026-08-05", "https://thumb.jpg", "Club Friendlies 2026-08-05 Chelsea vs Juventus");

        ExternalContentDraft draft = mapper.toDraft(event);

        assertThat(draft.type()).isEqualTo(ContentType.SPORT);
        assertThat(draft.source()).isEqualTo(ContentSource.SPORTS_DB);
        assertThat(draft.externalId()).isEqualTo("1");
        assertThat(draft.title()).isEqualTo("Chelsea vs Juventus");
        assertThat(draft.description()).isEqualTo("Club Friendlies 2026-08-05 Chelsea vs Juventus");
        assertThat(draft.thumbnailUrl()).isEqualTo("https://thumb.jpg");
        assertThat(draft.tags()).contains("Soccer", "Club Friendlies");
    }

    @Test
    @DisplayName("filename이 비어 있으면 description은 eventName으로 대체된다")
    void toDraft_blankFilename_fallsBackToEventName() {
        SportsDbEventSummary nullFilename = new SportsDbEventSummary(
                "1", "Chelsea vs Juventus", "Club Friendlies", "Soccer", "2026-08-05", "https://thumb.jpg", null);
        SportsDbEventSummary blankFilename = new SportsDbEventSummary(
                "1", "Chelsea vs Juventus", "Club Friendlies", "Soccer", "2026-08-05", "https://thumb.jpg", "  ");

        assertThat(mapper.toDraft(nullFilename).description()).isEqualTo("Chelsea vs Juventus");
        assertThat(mapper.toDraft(blankFilename).description()).isEqualTo("Chelsea vs Juventus");
    }

    @Test
    @DisplayName("eventName과 filename이 모두 null이면 description은 빈 문자열로 매핑된다")
    void toDraft_nullEventNameAndFilename_mapsToEmptyDescription() {
        SportsDbEventSummary event = new SportsDbEventSummary(
                "1", null, "Club Friendlies", "Soccer", "2026-08-05", "https://thumb.jpg", null);

        ExternalContentDraft draft = mapper.toDraft(event);

        assertThat(draft.description()).isEmpty();
    }

    @Test
    @DisplayName("sport/leagueName이 비어 있으면 태그에서 제외된다")
    void toDraft_blankSportAndLeague_excludedFromTags() {
        SportsDbEventSummary event = new SportsDbEventSummary(
                "1", "Chelsea vs Juventus", "", null, "2026-08-05", "https://thumb.jpg", "filename");

        ExternalContentDraft draft = mapper.toDraft(event);

        assertThat(draft.tags()).isEmpty();
    }
}