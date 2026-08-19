/*
 * OAuth Provider가 제공하는 고유 사용자 식별자를
 * 모두의 플리 사용자 계정과 연결하는 테이블
 *
 * 이메일은 변경될 수 있으므로 OAuth 사용자를 식별하는 기준으로 사용하지 않고,
 * provider와 provider_user_id 조합을 외부 계정의 안정적인 식별자로 사용
 */
CREATE TABLE oauth_accounts
(
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    user_id          UUID                        NOT NULL,
    provider         VARCHAR(20)                 NOT NULL,
    provider_user_id VARCHAR(255)                NOT NULL,

    /*
     * 사용자가 삭제되면 더 이상 의미가 없는 OAuth 연결 정보도
     * 함께 삭제하여 고아 데이터를 남기지 않는다.
     */
    CONSTRAINT fk_oauth_accounts_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    /*
     * 같은 Provider의 동일 외부 계정이 여러 서비스 사용자에게
     * 연결되는 것을 데이터베이스에서 차단
     */
    CONSTRAINT uk_oauth_accounts_provider_user
        UNIQUE (provider, provider_user_id),

    /*
     * 한 명의 서비스 사용자가 같은 Provider의 계정을
     * 여러 개 연결하는 것을 차단
     *
     * Google, Kakao, Naver 계정은 각각 하나씩 연결할 수 있다.
     */
    CONSTRAINT uk_oauth_accounts_user_provider
        UNIQUE (user_id, provider),

    /*
     * 애플리케이션의 OAuthProvider enum과 데이터베이스에 저장되는
     * Provider 값을 일치시킨다.
     */
    CONSTRAINT ck_oauth_accounts_provider
        CHECK (provider IN ('GOOGLE', 'KAKAO', 'NAVER')),

    /*
     * null뿐 아니라 빈 문자열이나 공백만 있는 Provider 사용자 ID도
     * 데이터베이스에 저장되지 않도록 최종 방어선을 둔다.
     */
    CONSTRAINT ck_oauth_accounts_provider_user_id
        CHECK (BTRIM(provider_user_id) <> '')
);

/*
 * 소셜 로그인만 사용해 가입한 사용자는 로컬 비밀번호가 없으므로
 * password_hash를 nullable 컬럼으로 변경
 *
 * 로컬 회원가입 사용자는 기존과 동일하게 애플리케이션 계층에서
 * 비밀번호를 필수로 입력하고 BCrypt 해시를 저장
 */
ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL;
