package com.mopl.content.storage;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * S3 연동이 준비되기 전까지 쓰는 임시 구현체입니다.
 * S3ThumbnailStorage로 교체예정.
 */
@Component
public class LocalPlaceholderThumbnailStorage implements ThumbnailStorage {

    private static final Pattern SAFE_EXTENSION_PATTERN = Pattern.compile("\\.[a-zA-Z0-9]{1,10}$");

    @Override
    public String upload(MultipartFile file) {
        String extension = extractSafeExtension(file.getOriginalFilename());
        return "https://placeholder.mopl.local/thumbnails/" + UUID.randomUUID() + extension;
    }

    private String extractSafeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        Matcher matcher = SAFE_EXTENSION_PATTERN.matcher(originalFilename);
        return matcher.find() ? matcher.group() : "";
    }
}