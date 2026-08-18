package com.mopl.user.security;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Refresh Token Family ID와 무작위 Secret을 하나의 Token 문자열로
 * 조합하고, 전달받은 Token에서 Family ID를 추출
 *
 * <p>Refresh Token 형식은 다음과 같습니다.</p>
 *
 * <pre>
 * {familyId}.{randomSecret}
 * </pre>
 *
 * <p>예를 들어 최초 로그인과 두 번의 Rotation 결과는 다음과 같이
 * Family ID는 유지되고 Secret만 변경됩니다.</p>
 *
 * <pre>
 * family-id.secret-1
 * family-id.secret-2
 * family-id.secret-3
 * </pre>
 *
 * <p>로그아웃 요청이 Rotation 이전 Cookie를 전달하더라도 Family ID를
 * 식별할 수 있으므로, Redis에서 해당 Family의 현재 활성 세션을
 * 폐기할 수 있습니다.</p>
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenFamilyCodec {

    /**
     * Family ID와 무작위 Secret을 구분하는 문자
     *
     * <p>RefreshTokenGenerator가 생성하는 Base64 URL-safe Secret에는
     * 마침표가 포함되지 않으므로 안전하게 구분자로 사용할 수 있습니다.</p>
     */
    private static final String TOKEN_DELIMITER = ".";

    /**
     * 32바이트를 패딩 없는 Base64 URL-safe 문자열로 인코딩한
     * Refresh Token Secret 형식
     *
     * <p>영문 대소문자, 숫자, 하이픈과 밑줄만 허용하며
     * 길이는 항상 43자입니다.</p>
     */
    private static final Pattern TOKEN_SECRET_PATTERN =
        Pattern.compile(
            "^[A-Za-z0-9_-]{43}$"
        );

    /**
     * Refresh Token의 예측 불가능한 256비트 Secret을 생성
     */
    private final RefreshTokenGenerator refreshTokenGenerator;

    /**
     * 새로운 로그인 세션을 위한 Refresh Token을 생성
     *
     * <p>로그인 시 새로운 Family ID를 만든 뒤, 해당 Family에 속하는
     * 첫 번째 무작위 Refresh Token을 생성합니다.</p>
     *
     * @return 새로운 Family ID와 Refresh Token 원문
     */
    public FamilyRefreshToken generateNewFamily() {
        UUID familyId =
            UUID.randomUUID();

        return generateForFamily(familyId);
    }

    /**
     * 기존 로그인 세션 Family에 속하는 새로운 Refresh Token을 생성
     *
     * <p>Refresh Token Rotation에서는 기존 Family ID를 유지하고
     * 무작위 Secret만 새로 생성합니다.</p>
     *
     * @param familyId 유지할 로그인 세션 Family ID
     * @return 같은 Family ID와 새로운 Secret으로 구성한 Refresh Token
     */
    public FamilyRefreshToken generateForFamily(
        UUID familyId
    ) {
        if (familyId == null) {
            throw new IllegalArgumentException(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
        }

        String randomSecret =
            refreshTokenGenerator.generate();

        String rawToken =
            familyId
                + TOKEN_DELIMITER
                + randomSecret;

        return new FamilyRefreshToken(
            familyId,
            rawToken
        );
    }

    /**
     * Cookie로 전달된 Refresh Token에서 Family ID를 추출
     *
     * <p>다음 조건 중 하나라도 만족하지 않으면 잘못된 토큰 형식이므로
     * 빈 Optional을 반환합니다.</p>
     *
     * <ul>
     *     <li>토큰이 null 또는 공백이 아니어야 합니다.</li>
     *     <li>구분자 마침표가 정확히 하나 존재해야 합니다.</li>
     *     <li>앞부분이 올바른 UUID 형식이어야 합니다.</li>
     *     <li>뒷부분이 43자의 Base64 URL-safe Secret이어야 합니다.</li>
     * </ul>
     *
     * <p>형식 오류를 예외로 외부에 직접 노출하지 않고 Optional.empty()로
     * 반환하면 Service가 모든 잘못된 Refresh Token을 동일한 401 응답으로
     * 처리할 수 있습니다.</p>
     *
     * @param rawToken Cookie로 전달된 Refresh Token 원문
     * @return 추출한 Family ID, 형식이 올바르지 않으면 빈 Optional
     */
    public Optional<UUID> parseFamilyId(
        String rawToken
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        int delimiterIndex =
            rawToken.indexOf(TOKEN_DELIMITER);

        /*
         * 구분자가 없거나 문자열의 처음 또는 끝에 있으면
         * Family ID와 Secret 중 하나가 비어 있는 형식
         */
        if (delimiterIndex <= 0
            || delimiterIndex == rawToken.length() - 1) {
            return Optional.empty();
        }

        /*
         * 구분자가 두 개 이상이면 사전에 정의한 토큰 형식이 아님.
         */
        if (delimiterIndex
            != rawToken.lastIndexOf(TOKEN_DELIMITER)) {
            return Optional.empty();
        }

        String familyIdPart =
            rawToken.substring(
                0,
                delimiterIndex
            );

        String secretPart =
            rawToken.substring(
                delimiterIndex + 1
            );

        /*
         * Redis를 조회하기 전에 Secret의 길이와 허용 문자를 검증해
         * 명백히 잘못된 입력이 저장소 계층까지 전달되지 않도록 한다.
         */
        if (!TOKEN_SECRET_PATTERN
            .matcher(secretPart)
            .matches()) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                UUID.fromString(familyIdPart)
            );
        } catch (IllegalArgumentException exception) {
            /*
             * 사용자 입력으로 전달되는 잘못된 토큰 형식은
             * 서버 오류가 아니므로 예외를 밖으로 전파하지 않는다.
             */
            return Optional.empty();
        }
    }
}
