package com.mopl.user.security;

import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * OAuth 전용 사용자가 로컬 로그인 수단을 추가할 때
 * 이메일 소유권 확인에 사용할 인증 코드를 생성
 *
 * <p>인증 코드는 실제 계정 인증에 사용되므로 예측 가능한
 * {@link java.util.Random} 대신 암호학적으로 안전한
 * {@link SecureRandom}을 사용합니다.</p>
 *
 * <p>생성된 원문은 이메일 발송과 사용자 입력 검증에만 사용하며
 * 로그 또는 Redis에 원문 그대로 저장하지 않습니다.</p>
 */
@Component
public class EmailVerificationCodeGenerator {

    /**
     * 생성 가능한 6자리 숫자 코드의 전체 범위
     */
    private static final int CODE_BOUND = 1_000_000;

    /**
     * 인증 코드 출력 형식
     *
     * <p>난수가 123처럼 짧더라도 000123으로 변환하여
     * 항상 6자리 문자열을 반환합니다.</p>
     */
    private static final String CODE_FORMAT = "%06d";

    /**
     * 여러 요청에서 재사용할 암호학적으로 안전한 난수 생성기
     */
    private final SecureRandom secureRandom =
        new SecureRandom();

    /**
     * 000000부터 999999 사이의 6자리 숫자 인증 코드를 생성
     *
     * @return 앞자리 0을 포함할 수 있는 6자리 숫자 문자열
     */
    public String generate() {
        int codeValue =
            secureRandom.nextInt(
                CODE_BOUND
            );

        return String.format(
            Locale.ROOT,
            CODE_FORMAT,
            codeValue
        );
    }
}
