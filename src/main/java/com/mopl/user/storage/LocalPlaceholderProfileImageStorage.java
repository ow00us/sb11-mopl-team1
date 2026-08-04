package com.mopl.user.storage;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 실제 프로필 이미지 저장소가 연동되기 전까지 사용하는 임시 구현체
 *
 * 현재는 파일을 실제로 저장하지 않고 고유한 임시 URL을 생성해 반환
 * 프로젝트에 이미 존재하는 LocalPlaceholderThumbnailStorage와 같은 방식
 *
 * 추후 S3 등의 저장소가 준비되면 이 구현체를 실제 구현체로 교체해야 함.
 */
@Component
public class LocalPlaceholderProfileImageStorage
    implements ProfileImageStorage {

    /**
     * 파일명 마지막에 있는 안전한 확장자만 추출하기 위한 정규식
     *
     * 영문과 숫자로 구성된 1~10자 확장자만 허용
     * 예: .png, .jpg, .jpeg, .webp
     */
    private static final Pattern SAFE_EXTENSION_PATTERN =
        Pattern.compile("\\.[a-zA-Z0-9]{1,10}$");

    /**
     * 프로필 이미지용 임시 URL을 생성
     *
     * UUID를 파일명으로 사용해 서로 다른 사용자가 같은 파일명을
     * 업로드하더라도 URL이 중복되지 않게 함.
     *
     * @param image 업로드할 프로필 이미지
     * @return 생성된 임시 프로필 이미지 URL
     */
    @Override
    public String upload(MultipartFile image) {
        String extension =
            extractSafeExtension(image.getOriginalFilename());

        return "https://placeholder.mopl.local/profile-images/"
            + UUID.randomUUID()
            + extension;
    }

    /**
     * 원본 파일명에서 허용된 형식의 확장자만 추출
     *
     * 원본 파일명이 없거나 안전한 확장자를 찾지 못하면
     * 확장자가 없는 빈 문자열을 반환
     *
     * @param originalFilename 업로드 파일의 원본 파일명
     * @return 점을 포함한 확장자 또는 빈 문자열
     */
    private String extractSafeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }

        Matcher matcher =
            SAFE_EXTENSION_PATTERN.matcher(originalFilename);

        return matcher.find() ? matcher.group() : "";
    }
}
