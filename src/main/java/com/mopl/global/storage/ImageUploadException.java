package com.mopl.global.storage;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;

/**
 * 이미지를 올리지 못했습니다.
 *
 * <p>업로드가 실패하면 URL 이 없습니다. 그런데 도메인은 이미 다른 필드를 바꾸는 중일 수
 * 있습니다. 예외로 끊어 그 트랜잭션이 커밋되지 않게 합니다. 조용히 null 을 돌려주면 이미지
 * 없는 레코드가 저장되고, 사용자에게는 성공으로 보입니다.
 */
public class ImageUploadException extends BusinessException {

    public ImageUploadException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
