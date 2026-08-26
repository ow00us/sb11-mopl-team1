package com.mopl.user.security;

import com.mopl.user.config.OAuthLocalCredentialProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 코드 원문을 HMAC-SHA256 해시로 변환
 *
 * <p>6자리 인증 코드는 경우의 수가 작으므로 단순 SHA-256만 사용하지 않고
 * 서버 비밀키를 포함한 HMAC-SHA256을 사용합니다.</p>
 *
 * <p>사용자 UUID와 대상 이메일도 HMAC 입력에 포함하여 동일한 인증 코드가
 * 다른 사용자나 다른 이메일의 인증에 재사용되지 않도록 합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationCodeHasher {

    private static final String HMAC_ALGORITHM =
        "HmacSHA256";

    private static final String VALUE_SEPARATOR =
        "\n";

    private final OAuthLocalCredentialProperties
        properties;

    /**
     * 사용자, 대상 이메일 및 인증 코드를 하나의 HMAC 값으로 변환
     *
     * @param userId 인증을 요청한 사용자 UUID
     * @param normalizedEmail 정규화된 실제 이메일
     * @param rawCode 이메일로 전달된 인증 코드 원문
     * @return 64자의 소문자 HMAC-SHA256 16진수 문자열
     */
    public String hash(
        UUID userId,
        String normalizedEmail,
        String rawCode
    ) {
        validateInput(
            userId,
            normalizedEmail,
            rawCode
        );

        try {
            Mac mac =
                Mac.getInstance(
                    HMAC_ALGORITHM
                );

            SecretKeySpec secretKey =
                new SecretKeySpec(
                    properties
                        .getVerificationSecret()
                        .getBytes(
                            StandardCharsets.UTF_8
                        ),
                    HMAC_ALGORITHM
                );

            mac.init(secretKey);

            String verificationValue =
                userId
                    + VALUE_SEPARATOR
                    + normalizedEmail
                    + VALUE_SEPARATOR
                    + rawCode;

            byte[] hashBytes =
                mac.doFinal(
                    verificationValue.getBytes(
                        StandardCharsets.UTF_8
                    )
                );

            return HexFormat.of()
                .formatHex(hashBytes);
        } catch (
            NoSuchAlgorithmException
            | InvalidKeyException exception
        ) {
            throw new IllegalStateException(
                "이메일 인증 코드 HMAC을 생성할 수 없습니다.",
                exception
            );
        }
    }

    private void validateInput(
        UUID userId,
        String normalizedEmail,
        String rawCode
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "이메일 인증 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (
            normalizedEmail == null
                || normalizedEmail.isBlank()
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 대상 이메일은 비어 있을 수 없습니다."
            );
        }

        if (
            rawCode == null
                || !rawCode.matches("^\\d{6}$")
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 코드는 6자리 숫자여야 합니다."
            );
        }
    }
}
