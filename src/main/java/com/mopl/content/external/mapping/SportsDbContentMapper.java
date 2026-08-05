package com.mopl.content.external.mapping;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SportsDbContentMapper {

    public ExternalContentDraft toDraft(SportsDbEventSummary event) {
        Set<String> tags = new HashSet<>();
        if (event.sport() != null && !event.sport().isBlank()) {
            tags.add(event.sport());
        }
        if (event.leagueName() != null && !event.leagueName().isBlank()) {
            tags.add(event.leagueName());
        }

        String description = event.filename() != null && !event.filename().isBlank()
                ? event.filename()
                : event.eventName();

        return new ExternalContentDraft(
                ContentType.SPORT,
                ContentSource.SPORTS_DB,
                event.idEvent(),
                event.eventName(),
                description == null ? "" : description,
                event.thumbnail(),
                tags
        );
    }
}