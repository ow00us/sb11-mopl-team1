package com.mopl.user.security;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 초기화에 사용할 임시 비밀번호를 생성
 *
 * <p>임시 비밀번호는 이메일을 통해 사용자에게 전달되고 실제 로그인에
 * 사용되는 인증 정보이므로 예측 가능한 {@link java.util.Random}이 아니라
 * 암호학적으로 안전한 {@link SecureRandom}을 사용합니다.</p>
 *
 * <p>생성된 원문은 이메일 발송과 BCrypt 인코딩에만 사용해야 하며,
 * 데이터베이스, Redis, API 응답 또는 애플리케이션 로그에
 * 저장하거나 출력해서는 안 됩니다.</p>
 */
@Component
public class TemporaryPasswordGenerator {

    /**
     * 생성할 임시 비밀번호 길이
     *
     * <p>현재 프로젝트의 비밀번호 정책인 8~72자 범위 안에서
     * 충분한 무작위성을 제공하면서 사용자가 이메일을 보고 직접 입력할
     * 수 있도록 16자를 사용합니다.</p>
     */
    private static final int PASSWORD_LENGTH = 16;

    /**
     * 혼동하기 쉬운 I, O를 제외한 영문 대문자
     */
    private static final String UPPERCASE_CHARACTERS =
        "ABCDEFGHJKLMNPQRSTUVWXYZ";

    /**
     * 혼동하기 쉬운 소문자 l을 제외한 영문 소문자
     */
    private static final String LOWERCASE_CHARACTERS =
        "abcdefghijkmnopqrstuvwxyz";

    /**
     * 혼동하기 쉬운 숫자 0, 1을 제외한 숫자
     */
    private static final String DIGIT_CHARACTERS =
        "23456789";

    /**
     * 회원가입 및 비밀번호 변경 정책에서 허용하는 특수문자 중
     * 이메일에서 비교적 구분하기 쉬운 문자
     */
    private static final String SPECIAL_CHARACTERS =
        "!@#$%^&*";

    /**
     * 임시 비밀번호의 나머지 문자를 선택할 전체 문자 집합
     */
    private static final String ALL_CHARACTERS =
        UPPERCASE_CHARACTERS
            + LOWERCASE_CHARACTERS
            + DIGIT_CHARACTERS
            + SPECIAL_CHARACTERS;

    /**
     * 암호학적으로 안전한 난수 생성기
     *
     * <p>SecureRandom은 내부 상태를 갱신하면서 다음 난수를 생성하므로
     * 비밀번호 생성마다 새 객체를 만들지 않고 재사용합니다.</p>
     */
    private final SecureRandom secureRandom =
        new SecureRandom();

    /**
     * 비밀번호 정책을 만족하는 임시 비밀번호를 생성
     *
     * <p>각 필수 문자 종류를 먼저 하나씩 배치하여 대문자, 소문자,
     * 숫자와 특수문자가 반드시 포함되도록 합니다. 나머지 위치는 전체
     * 문자 집합에서 무작위로 선택합니다.</p>
     *
     * <p>필수 문자가 항상 문자열 앞부분에 위치하면 생성 규칙을 추측할 수
     * 있으므로 모든 문자를 생성한 뒤 Fisher-Yates 방식으로 순서를
     * 무작위로 섞습니다.</p>
     *
     * @return 16자의 임시 비밀번호 원문
     */
    public String generate() {
        char[] password =
            new char[PASSWORD_LENGTH];

        /*
         * 현재 프로젝트 비밀번호 정책을 항상 만족하도록
         * 필수 문자 종류를 하나씩 먼저 배치
         */
        password[0] =
            randomCharacter(
                UPPERCASE_CHARACTERS
            );

        password[1] =
            randomCharacter(
                LOWERCASE_CHARACTERS
            );

        password[2] =
            randomCharacter(
                DIGIT_CHARACTERS
            );

        password[3] =
            randomCharacter(
                SPECIAL_CHARACTERS
            );

        /*
         * 나머지 위치는 허용된 전체 문자 집합에서 무작위로 선택
         */
        for (
            int index = 4;
            index < PASSWORD_LENGTH;
            index++
        ) {
            password[index] =
                randomCharacter(
                    ALL_CHARACTERS
                );
        }

        /*
         * 필수 문자 종류가 처음 네 자리에 고정되지 않도록
         * 배열 전체의 순서를 무작위로 섞는다.
         */
        shuffle(password);

        return new String(password);
    }

    /**
     * 전달받은 문자 집합에서 한 문자를 안전하게 선택
     *
     * @param characters 문자를 선택할 문자 집합
     * @return 무작위로 선택한 문자
     */
    private char randomCharacter(
        String characters
    ) {
        int randomIndex =
            secureRandom.nextInt(
                characters.length()
            );

        return characters.charAt(
            randomIndex
        );
    }

    /**
     * Fisher-Yates 알고리즘으로 문자 배열의 순서를 무작위로 섞음
     *
     * <p>배열의 마지막 위치부터 앞으로 이동하면서 현재 위치와
     * 앞쪽의 무작위 위치를 교환합니다. 모든 순열이 동일한 확률로
     * 선택될 수 있습니다.</p>
     *
     * @param characters 순서를 섞을 문자 배열
     */
    private void shuffle(
        char[] characters
    ) {
        for (
            int index = characters.length - 1;
            index > 0;
            index--
        ) {
            int randomIndex =
                secureRandom.nextInt(
                    index + 1
                );

            char temporary =
                characters[index];

            characters[index] =
                characters[randomIndex];

            characters[randomIndex] =
                temporary;
        }
    }
}
