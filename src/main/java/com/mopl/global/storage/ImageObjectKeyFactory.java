package com.mopl.global.storage;

import com.mopl.global.exception.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * 업로드할 객체의 키를 만듭니다.
 *
 * <p>원본 파일명을 쓰지 않습니다. 두 사용자가 같은 이름을 올리면 뒤가 앞을 덮고, 이름에
 * {@code ../} 나 절대 경로가 들어오면 의도한 구분자 밖으로 나갑니다. 파일명은 사용자가 정하는
 * 값이므로 키의 재료로 쓰지 않는 것이 가장 확실합니다.
 *
 * <p>확장자도 파일명이 아니라 Content-Type 에서 얻습니다. 파일명의 확장자는 내용과 무관하게
 * 붙일 수 있어 검증한 형식과 저장한 이름이 어긋날 수 있습니다.
 */
final class ImageObjectKeyFactory {

    /**
     * 허용한 Content-Type 과 확장자의 대응입니다.
     *
     * <p>여기 없는 형식은 키를 만들 수 없습니다. 허용 목록을 설정으로 넓혔는데 대응이 없으면
     * 확장자 없는 객체가 생기므로, 그 상태를 조용히 넘기지 않습니다.
     */
    private static final Map<String, String> EXTENSIONS = Map.of(
        "image/jpeg", ".jpg",
        "image/png", ".png",
        "image/webp", ".webp",
        "image/gif", ".gif"
    );

    private ImageObjectKeyFactory() {
    }

    /**
     * 구분자 아래에 충돌하지 않는 키를 만듭니다.
     *
     * @param prefix 도메인이 정한 구분자
     * @param contentType 검증을 마친 Content-Type
     */
    static String create(String prefix, String contentType) {
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "확장자를 정할 수 없는 이미지 형식입니다. contentType=" + contentType);
        }
        return normalizePrefix(prefix) + UUID.randomUUID() + extension;
    }

    /**
     * 구분자를 정리합니다.
     *
     * <p>앞뒤 구분 기호를 다듬어 설정에 {@code /} 를 붙였든 안 붙였든 같은 키가 나오게 합니다.
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.strip();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }
}
