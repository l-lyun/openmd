# 2단계 이메일 회원가입 서버 설계

- 상태: 구현됨
- 적용 영역: `server/`
- 관련 API 계약: [인증 API](../../../docs/contracts/api/authentication.md)
- 관련 데이터 계약: [사용자·인증 데이터](../../../docs/contracts/data/authentication.md)

## 책임 경계

`TwoStepSignUpService`가 이메일 인증부터 최종 사용자 생성까지를 담당하고, 로그인·갱신·로그아웃은 기존 `AuthService`가 담당한다.

```text
TwoStepSignUpService
  ├─ EmailVerificationStore
  │    └─ RedisEmailVerificationStore
  ├─ SignUpCredentialStore
  │    └─ RedisSignUpCredentialStore
  ├─ UserRepository + TransactionOperations
  ├─ RefreshTokenService
  └─ AccessTokenService
```

- 이메일 인증 상태의 Redis key에는 정규화 이메일의 HMAC digest만 사용한다.
- 코드 성공 시 UUID v4 원문은 응답에 한 번만 반환하고, Redis key에는 SHA-256 digest만 사용한다.
- 가입 자격 값은 표시 이메일, 정규화 이메일과 인증 시각이며 TTL은 15분이다.
- `RedisSignUpCredentialStore`는 `HSET`과 `PEXPIRE`를 단일 Lua 실행으로 묶어 TTL 없는 가입 자격이 남지 않게 한다.

## 최종 가입 순서

최종 가입은 다음 순서를 유지한다.

1. UUID v4 가입 자격을 검증하고 Redis에서 인증 이메일 정보를 조회한다.
2. 비밀번호, NFC 닉네임과 두 필수 약관 버전을 검증한다.
3. `TransactionTemplate` 안에서 `ACTIVE` 사용자를 `saveAndFlush`한다.
4. 트랜잭션이 성공해 반환된 뒤 Redis 가입 자격을 삭제한다.
5. Refresh Token과 Access Token을 발급한다.

DB 저장 또는 commit이 실패하면 Redis 가입 자격을 소비하지 않는다. 가입 자격 삭제가 실패하면 이미 확정된 사용자 행을 되돌리지 않고 15분 TTL과 이메일·닉네임 unique 제약에 의존한다. 최종 가입 응답이 유실되면 브라우저는 refresh를 한 번 시도하고, 세션을 복구하지 못한 브라우저와 세션 body를 잃은 네이티브는 로그인으로 복구한다. 가입 완료를 반복 호출해 세션을 다시 발급하지 않는다.

닉네임 사전 확인은 예약이 아니다. 서비스의 사전 조회와 MySQL의 case-insensitive `UNIQUE(nickname)`을 함께 사용하며, 최종 선점 충돌은 `AUTH_010`으로 변환한다.

## 마이그레이션

`V3__add_signup_profile_and_terms.sql`은 운영 데이터를 삭제하거나 확인되지 않은 동의 값을 채우지 않는다. 기존 사용자는 버전 동의 기록 도입 전 데이터이므로 새 컬럼을 nullable로 추가하고, 새 가입 경로만 닉네임과 두 약관의 버전·시각을 모두 기록한다.

기존 사용자에 대한 실제 동의 근거를 확보한 뒤 별도 감사 가능한 마이그레이션으로 `NOT NULL`을 적용해야 한다. 현재 전환 마이그레이션에서 임의 시각이나 임시 약관 버전을 backfill하지 않는다.

## 검증

- 서비스 단위 테스트: 사용자 행 없는 이메일 인증, UUID v4 발급, NFC 닉네임, 약관 검증, DB 완료 후 가입 자격 소비, rollback 보존, 삭제 장애 허용
- MVC·보안 테스트: 네이티브/브라우저 가입 응답, HttpOnly Cookie, 브라우저 Origin·CSRF 경계
- MySQL 8.4·Redis 7.4 통합 테스트: Flyway V3, case-insensitive 닉네임 unique, 이메일 인증 TTL, 가입 자격의 원자 저장과 15분 TTL
