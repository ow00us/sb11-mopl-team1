package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Refresh Token Family 문자열 생성 및 파싱 규칙을 검증
 *
 * <p>무작위 Secret 자체의 길이와 중복 방지는
 * RefreshTokenGeneratorTest에서 검증하므로,
 * 이 테스트에서는 Family ID와 Secret의 조합 및 파싱에 집중합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenFamilyCodecTest {

    /**
     * RefreshTokenGenerator가 생성하는 43자의
     * Base64 URL-safe 테스트 Secret
     */
    private static final String TEST_SECRET =
        "A".repeat(43);

    /**
     * Rotation 후 생성할 다른 테스트 Secret
     */
    private static final String ROTATED_SECRET =
        "B".repeat(43);

    /**
     * 예측 가능한 Secret을 반환하도록 제어하는 Generator Mock
     */
    @Mock
    RefreshTokenGenerator refreshTokenGenerator;

    /**
     * Generator Mock을 주입한 테스트 대상 Codec
     */
    @InjectMocks
    RefreshTokenFamilyCodec refreshTokenFamilyCodec;

    @Test
    @DisplayName("로그인 시 새로운 Family ID와 Refresh Token을 생성한다")
    void generateNewFamily_createsFamilyIdAndRawToken() {
        // given
        when(refreshTokenGenerator.generate())
            .thenReturn(TEST_SECRET);

        // when
        FamilyRefreshToken result =
            refreshTokenFamilyCodec.generateNewFamily();

        // then
        assertThat(result.familyId())
            .isNotNull();

        /*
         * 생성된 Refresh Token은
         * familyId.secret 형식이어야 한다.
         */
        assertThat(result.rawToken())
            .isEqualTo(
                result.familyId()
                    + "."
                    + TEST_SECRET
            );

        verify(refreshTokenGenerator)
            .generate();
    }

    @Test
    @DisplayName("Rotation 시 기존 Family ID를 유지하고 Secret만 교체한다")
    void generateForFamily_keepsFamilyIdAndChangesSecret() {
        // given
        UUID familyId =
            UUID.randomUUID();

        when(refreshTokenGenerator.generate())
            .thenReturn(ROTATED_SECRET);

        // when
        FamilyRefreshToken result =
            refreshTokenFamilyCodec.generateForFamily(
                familyId
            );

        // then
        assertThat(result.familyId())
            .isEqualTo(familyId);

        assertThat(result.rawToken())
            .isEqualTo(
                familyId
                    + "."
                    + ROTATED_SECRET
            );

        verify(refreshTokenGenerator)
            .generate();
    }

    @Test
    @DisplayName("Family ID 없이 Rotation Token을 생성할 수 없다")
    void generateForFamily_failsWhenFamilyIdIsNull() {
        assertThatThrownBy(() ->
            refreshTokenFamilyCodec.generateForFamily(
                null
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
    }

    @Test
    @DisplayName("올바른 Refresh Token에서 Family ID를 추출한다")
    void parseFamilyId_extractsFamilyIdFromValidToken() {
        // given
        UUID familyId =
            UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + TEST_SECRET;

        // when
        Optional<UUID> result =
            refreshTokenFamilyCodec.parseFamilyId(
                rawToken
            );

        // then
        assertThat(result)
            .contains(familyId);
    }

    @ParameterizedTest(name = "[{index}] 잘못된 토큰: {0}")
    @MethodSource("invalidRefreshTokens")
    @DisplayName("잘못된 Refresh Token 형식에서는 Family ID를 반환하지 않는다")
    void parseFamilyId_returnsEmptyForInvalidToken(
        String rawToken
    ) {
        // when
        Optional<UUID> result =
            refreshTokenFamilyCodec.parseFamilyId(
                rawToken
            );

        // then
        assertThat(result)
            .isEmpty();
    }

    /**
     * Family ID 파싱에 실패해야 하는 토큰 형식을 제공
     *
     * @return 잘못된 Refresh Token 입력 목록
     */
    private static Stream<Arguments> invalidRefreshTokens() {
        UUID familyId =
            UUID.randomUUID();

        return Stream.of(
            /*
             * Token 자체가 없는 경우
             */
            Arguments.of((String) null),
            Arguments.of(""),
            Arguments.of("   "),

            /*
             * 구분자나 필수 부분이 없는 경우
             */
            Arguments.of("token-without-delimiter"),
            Arguments.of("." + TEST_SECRET),
            Arguments.of(familyId + "."),

            /*
             * 구분자가 두 개 이상인 경우
             */
            Arguments.of(
                familyId
                    + "."
                    + TEST_SECRET
                    + ".extra"
            ),

            /*
             * Family ID가 UUID 형식이 아닌 경우
             */
            Arguments.of(
                "not-a-uuid."
                    + TEST_SECRET
            ),

            /*
             * Secret 길이가 43자가 아닌 경우
             */
            Arguments.of(
                familyId
                    + "."
                    + "A".repeat(42)
            ),
            Arguments.of(
                familyId
                    + "."
                    + "A".repeat(44)
            ),

            /*
             * Secret에 Base64 URL-safe 형식이 아닌 문자가 포함된 경우
             */
            Arguments.of(
                familyId
                    + "."
                    + "A".repeat(42)
                    + "!"
            )
        );
    }

    @Test
    @DisplayName("FamilyRefreshToken은 null Family ID를 허용하지 않는다")
    void familyRefreshToken_failsWhenFamilyIdIsNull() {
        assertThatThrownBy(() ->
            new FamilyRefreshToken(
                null,
                "raw-token"
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
    }

    @ParameterizedTest
    @MethodSource("emptyRawTokens")
    @DisplayName("FamilyRefreshToken은 비어 있는 원문을 허용하지 않는다")
    void familyRefreshToken_failsWhenRawTokenIsEmpty(
        String rawToken
    ) {
        assertThatThrownBy(() ->
            new FamilyRefreshToken(
                UUID.randomUUID(),
                rawToken
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token 원문은 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("FamilyRefreshToken 문자열에는 Refresh Token 원문을 노출하지 않는다")
    void familyRefreshToken_doesNotExposeRawTokenInToString() {
        // given
        UUID familyId = UUID.randomUUID();

        String rawToken =
            familyId
                + "."
                + TEST_SECRET;

        FamilyRefreshToken familyRefreshToken =
            new FamilyRefreshToken(
                familyId,
                rawToken
            );

        // when
        String result =
            familyRefreshToken.toString();

        // then
        assertThat(result)
            .contains(familyId.toString())
            .contains("rawToken=<redacted>")
            .doesNotContain(rawToken)
            .doesNotContain(TEST_SECRET);
    }

    /**
     * FamilyRefreshToken 생성에 실패해야 하는 빈 원문을 제공
     *
     * @return null, 빈 문자열과 공백 문자열
     */
    private static Stream<Arguments> emptyRawTokens() {
        return Stream.of(
            Arguments.of((String) null),
            Arguments.of(""),
            Arguments.of("   ")
        );
    }
}
