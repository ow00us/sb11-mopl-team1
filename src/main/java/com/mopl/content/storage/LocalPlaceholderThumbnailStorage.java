package com.mopl.content.storage;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * S3 연동이 준비되기 전까지 쓰는 임시 구현체입니다.
 * S3ThumbnailStorage로 교체예정.
 */
@Component
public class LocalPlaceholderThumbnailStorage implements ThumbnailStorage {

    @Override
    public String upload(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "thumbnail" : file.getOriginalFilename();
        return "https://placeholder.mopl.local/thumbnails/" + UUID.randomUUID() + "-" + name;
    }
}
