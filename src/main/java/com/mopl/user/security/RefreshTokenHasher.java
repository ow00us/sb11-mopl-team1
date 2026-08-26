package com.mopl.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 원문을 데이터베이스 저장용 SHA-256 해시로 변환
 *
 * Refresh Token 원문은 클라이언트의 HttpOnly Cookie로 전달하고,
 * 서버 데이터베이스에는 이 클래스가 생성한 해시값만 저장
 *
 * 이를 통해 데이터베이스가 노출되더라도 저장된 값을
 * Refresh Token 원문처럼 바로 사용할 수 없도록 한다.
 */
@Component
public class RefreshTokenHasher {

    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Refresh Token 원문을 SHA-256 해시의 16진수 문자열로 변환
     *
     * 같은 원문은 항상 같은 해시를 생성하므로,
     * 재발급 요청에서 Cookie의 원문을 다시 해시하여
     * 데이터베이스의 token_hash 컬럼으로 세션을 조회할 수 있다.
     *
     * SHA-256 결과는 32바이트이고, 바이트마다 16진수 두 글자로 표현하므로
     * 반환되는 문자열의 길이는 항상 64자
     *
     * @param rawToken 클라이언트에 전달하거나 Cookie에서 받은 Refresh Token 원문
     * @return 64자의 소문자 SHA-256 16진수 해시
     * @throws IllegalArgumentException rawToken이 null인 경우
     * @throws IllegalStateException 실행 환경에서 SHA-256을 사용할 수 없는 경우
     */
    public String hash(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException(
                "Refresh Token 원문은 null일 수 없습니다."
            );
        }

        try {
            /*
             * MessageDigest는 스레드 안전한 객체가 아니므로
             * 여러 요청이 동시에 사용하는 필드로 보관하지 않고
             * 해시를 생성할 때마다 새 인스턴스를 생성
             */
            MessageDigest messageDigest =
                MessageDigest.getInstance(HASH_ALGORITHM);

            byte[] tokenBytes =
                rawToken.getBytes(StandardCharsets.UTF_8);

            byte[] hashBytes =
                messageDigest.digest(tokenBytes);

            return HexFormat.of()
                .formatHex(hashBytes);
        } catch (NoSuchAlgorithmException exception) {
            /*
             * SHA-256은 Java 표준 구현에서 필수로 제공되는 알고리즘
             * 정상적인 Java 실행 환경에서는 발생하지 않지만,
             * MessageDigest API가 체크 예외를 요구하므로
             * 애플리케이션 설정 문제를 나타내는 IllegalStateException으로 변환
             */
            throw new IllegalStateException(
                "SHA-256 해시 알고리즘을 사용할 수 없습니다.",
                exception
            );
        }
    }
}
