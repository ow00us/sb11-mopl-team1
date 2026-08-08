package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.global.common.CursorResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ContentService {

    ContentDto create(ContentCreateRequest request, MultipartFile thumbnail);

    ContentDto get(UUID contentId);

    CursorResponse<ContentDto> getList(
            String typeEqual,
            String keywordLike,
            List<String> tagsIn,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection
    );

    ContentDto update(UUID contentId, ContentUpdateRequest request, MultipartFile thumbnail);

    void delete(UUID contentId);
}