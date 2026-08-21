# API 계약 초안: 이메일 기반 자체 인증

- 상태: 초안
- 소유 영역: 서버 사용자·인증
- 소비 영역: 웹·앱 클라이언트
- 관련 기능명세: [이메일 기반 자체 인증](../../features/01-local-auth.md)
- 관련 데이터: [사용자·인증 데이터](../data/authentication.md)
- 관련 웹 설계: [웹 인증 상태·토큰·API 통합 설계](../../../web/docs/technical/authentication.md)
- 전환 상태: 브라우저 HttpOnly Cookie와 2단계 회원가입 서버 구현 완료, 웹 1단계 인증 메일 호출 전환 필요

## 목적

6자리 이메일 인증 코드를 전제로 한 2단계 가입, 닉네임 확인, 필수 동의, 로그인, 인증 상태 갱신, 현재 세션 로그아웃과 현재 사용자 확인의 클라이언트·서버 경계를 정의한다.

## 회원가입 계약 전환 상태

- 아래의 2단계 회원가입 계약은 서버에 구현됐다. 인증 메일은 `POST /api/v1/auth/email-verifications`, 최종 네이티브 가입은 `POST /api/v1/auth/sign-ups`, 최종 브라우저 가입은 `POST /api/v1/auth/web/sign-ups`를 사용한다.
- 기존의 “`POST /api/v1/auth/sign-ups`에서 대기 사용자 생성” 계약은 서버에서 제거됐다. 현재 웹 1단계 호출은 아직 기존 경로와 email+password body를 사용하므로 서버와 함께 배포하기 전에 인증 메일 endpoint로 전환하고 개발 우회를 제거해야 한다.
- 이메일 인증 완료는 가입 계속 자격을 발급하고, 닉네임·필수 동의의 최종 제출과 세션 발급은 최종 가입 endpoint가 처리한다.
- 향후 소셜 가입의 미완료 상태, 재진입과 `social_accounts` 계약은 소셜 로그인 서버 작업에서 확정하며 이 문서의 현재 구현 범위가 아니다.

## 공통 규칙

### 확인된 관례와 요구

- 현재 서버의 공통 응답 모양인 `{ "success", "data", "error" }`를 유지한다.
- 보호 API는 유효한 Access Token에서 얻은 내부 `userId`로 소유권을 판단한다. 요청 본문의 `userId`를 신뢰하지 않는다.
- 비밀번호, 이메일 인증 코드, Access/Refresh Token을 URL path/query에 넣지 않는다.
- 공개 인증 오류는 이메일 존재 여부, 비밀번호 일치 여부, 정지 여부를 불필요하게 구분하지 않는다.
- 오류의 `message`는 변경 가능한 사용자 안내이며, 클라이언트 분기는 안정적인 `error.code`만 사용한다.

### 구현에서 확정한 사항

- 기준 경로는 `/api/v1`으로 하고 JSON 요청·응답을 사용한다.
- Access Token은 HMAC SHA-256으로 서명한 JWT이며 보호 API의 `Authorization: Bearer <access token>`으로 전달한다.
- Access Token은 발급 시점부터 정확히 5분 동안 유효하다.
- 초기 API의 Refresh Token은 요청·응답 JSON 본문으로 전달한다. 네이티브 앱은 수신 즉시 OS 보안 저장소에 보관해야 한다.
- 공개 `/api/v1/auth/**` 요청에 우연히 잘못된 Bearer Token이 포함되어도 공개 인증 흐름을 단락하지 않는다. 보호 API의 잘못되거나 만료된 Bearer Token은 `AUTH_005`로 거절한다.
- CORS 허용 origin은 환경설정으로 제한하며 개발 기본값은 웹 개발 서버 `http://localhost:5173`이다. `OPTIONS` preflight와 `GET`, `POST`, `DELETE`, `Authorization`, `Content-Type`을 허용하고 JSON body 토큰 전송을 사용하므로 credentialed CORS는 사용하지 않는다.
- 모든 시각은 ISO 8601 UTC 문자열로 반환한다.

### 브라우저 전환 목표

- 기존 Redis 기반 Refresh Token Rotation과 재사용 탐지 규칙은 유지한다. 바꾸는 범위는 브라우저와 서버 사이의 Refresh Token 전달 경계다.
- 브라우저 웹은 Refresh Token 원문을 JSON body로 받거나 보내지 않고, 서버가 발급·회전·삭제하는 HttpOnly Cookie만 사용한다.
- Access Token과 `accessExpiresAt`은 기존처럼 JSON body로 반환하고 보호 API의 Bearer Token으로 사용한다.
- 네이티브 앱용 body 계약과 브라우저 cookie 계약은 endpoint surface를 분리한다. 같은 endpoint가 Cookie와 body 중 하나를 암묵적으로 선택하거나 User-Agent로 소비자를 판별하지 않는다.
- 기존 앱 소비자는 아래의 body 계약을 계속 사용하고, 웹은 브라우저 전용 surface로 전환한다.

## 엔드포인트 목록

| 기능 | Method / Path | 인증 | 성공 상태 |
| --- | --- | --- | --- |
| 가입용 인증 메일 발송·재발송 | `POST /api/v1/auth/email-verifications` | 불필요 | `202 Accepted` |
| 이메일 인증 완료 | `POST /api/v1/auth/email-verifications/confirm` | 이메일+6자리 코드 | `200 OK` |
| 닉네임 사용 가능 확인 | `POST /api/v1/auth/nickname-availability` | 불필요 | `200 OK` |
| 네이티브 가입 완료·세션 발급 | `POST /api/v1/auth/sign-ups` | 가입 계속 자격 | `201 Created` |
| 로그인 | `POST /api/v1/auth/sessions` | 불필요 | `200 OK` |
| 인증 상태 갱신 | `POST /api/v1/auth/sessions/refresh` | Refresh Token | `200 OK` |
| 현재 세션 로그아웃 | `DELETE /api/v1/auth/sessions/current` | Refresh Token | `200 OK` |
| 현재 사용자 | `GET /api/v1/users/me` | Access Token | `200 OK` |

### 브라우저 웹 전용 세션 surface

가입·이메일 인증과 `GET /api/v1/users/me`는 기존 endpoint를 공통으로 사용한다. Refresh Token의 전달 방식이 다른 세션 endpoint만 분리한다.

| 기능 | Method / Path | Refresh Token 전달 | 성공 상태 |
| --- | --- | --- | --- |
| 브라우저 가입 완료 | `POST /api/v1/auth/web/sign-ups` | 성공 응답 `Set-Cookie` | `201 Created` |
| 브라우저 로그인 | `POST /api/v1/auth/web/sessions` | 성공 응답 `Set-Cookie` | `200 OK` |
| 브라우저 인증 상태 갱신 | `POST /api/v1/auth/web/sessions/refresh` | 요청 Cookie, 성공 응답 `Set-Cookie` | `200 OK` |
| 브라우저 현재 세션 로그아웃 | `DELETE /api/v1/auth/web/sessions/current` | 요청 Cookie, 응답 만료 `Set-Cookie` | `200 OK` |

- 기존 `/api/v1/auth/sessions*`는 네이티브 앱과 마이그레이션 중인 소비자를 위한 body surface로 유지한다.
- 브라우저 endpoint는 Refresh Token body를 허용하지 않고, 기존 body endpoint는 Cookie를 인증 근거로 읽지 않는다.
- WebView가 원격 웹을 로드하고 웹 JavaScript가 API를 호출하는 경우 브라우저 surface와 WebView cookie jar를 사용한다. React Native 코드가 직접 API를 호출하는 경우 native surface와 OS 보안 저장소를 사용한다. User-Agent가 아니라 호출 실행 경계로 선택한다.

## 가입용 인증 메일 발송·재발송

`POST /api/v1/auth/email-verifications`

```json
{
  "email": "learner@example.com"
}
```

- `email`: 필수, 이메일 형식, 정규화 전 최대 길이 제한 적용.
- 이메일 입력 옆의 명시적 발송 행동에서 호출한다. 같은 이메일의 재발송에도 같은 endpoint를 사용한다.

제안 응답:

```json
{
  "success": true,
  "data": {
    "verificationRequired": true,
    "resendAvailableAt": "2026-08-20T00:01:00Z"
  },
  "error": null
}
```

- 같은 이메일 요청이 동시에 들어와도 인증 상태를 중복 생성하거나 서로 다른 현재 코드를 남기지 않는다.
- 이메일 존재 여부 노출을 줄이기 위해 신규·대기·활성 이메일에 가능한 한 같은 `202` 응답을 반환한다.
- 새 코드를 발급하면 이전 코드를 원자적으로 무효화한다. 60초 안의 요청은 새 메일을 보내지 않되 같은 접수 모양을 유지한다.

## 이메일 인증 완료

`POST /api/v1/auth/email-verifications/confirm`

```json
{
  "email": "learner@example.com",
  "code": "A7K9M2"
}
```

```json
{
  "success": true,
  "data": {
    "emailVerified": true,
    "signUpToken": "61d67fa8-1a2b-4f35-94fc-16ec63551b15",
    "nextAction": "COMPLETE_PROFILE"
  },
  "error": null
}
```

- `code`는 trim 후 uppercase하고 alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`에 속하는 정확히 6자리인지 확인한다.
- 서버는 이메일 인증 상태의 keyed digest, TTL, 실패 횟수를 원자적으로 검증한다.
- 잘못된 코드 검증은 실패 횟수를 원자적으로 증가시키고, 5회 실패하면 현재 코드를 무효화한다.
- `signUpToken`은 `UUID.randomUUID()`로 만든 UUID v4이며 이메일 인증 결과에 묶인 15분 수명의 일회성 가입 계속 자격이다. 서버는 원문 대신 SHA-256 digest와 정규화·표시 이메일, 인증 시각을 Redis에 저장한다.
- 클라이언트는 `signUpToken`을 메모리에서만 보관한다. URL·로그·분석 사건·DB·`localStorage`·`sessionStorage`에 넣지 않으며 새로고침, 앱 종료, 만료 또는 새 이메일 인증으로 잃거나 무효화되면 이메일 인증부터 다시 진행한다.
- 이메일 가입 클라이언트는 비밀번호 확인 값을 전송하지 않는다. 1단계에서 확인한 비밀번호 하나를 최종 가입 완료 요청에만 전송한다.

## 닉네임 사용 가능 확인

`POST /api/v1/auth/nickname-availability`

```json
{
  "nickname": "공부왕7"
}
```

```json
{
  "success": true,
  "data": {
    "available": true,
    "checkedNickname": "공부왕7"
  },
  "error": null
}
```

- 닉네임은 화면에 보이는 글자 기준 2~10자이고 공백 없이 한글·영문·숫자만 허용한다.
- 서버는 닉네임을 NFC로 정규화한 뒤 같은 단일 값을 표시와 저장에 사용한다. 별도 비교용 컬럼을 만들지 않고 대소문자를 구분하지 않는 DB collation과 `UNIQUE(nickname)`으로 영문 대소문자 차이와 동시 선점을 차단한다.
- `available=true`는 예약이나 소유권 획득이 아니다. 클라이언트는 `checkedNickname`과 현재 입력이 같을 때만 확인 완료로 취급하고, 입력 변경 즉시 결과를 폐기한다.

## 가입 완료와 세션 발급

네이티브: `POST /api/v1/auth/sign-ups`

브라우저: `POST /api/v1/auth/web/sign-ups`

```json
{
  "signUpToken": "61d67fa8-1a2b-4f35-94fc-16ec63551b15",
  "password": "<redacted>",
  "nickname": "공부왕7",
  "agreements": [
    { "termsId": "SERVICE_TERMS", "version": "TEMP-2026-08-20" },
    { "termsId": "PRIVACY_COLLECTION", "version": "TEMP-2026-08-20" }
  ]
}
```

- `password`: 이메일 가입에만 필수이며 8~64자, 영문자·숫자 각각 1자 이상, 공백 불가, 특수문자 선택이다. 비밀번호 확인 값은 전송하지 않는다.
- `agreements`는 단순 boolean이 아니라 사용자가 동의한 약관 식별자와 버전의 목록이다. 예시의 `TEMP-2026-08-20`은 현재 프론트엔드 임시 전문용 값이며 법률 검토가 끝난 운영 약관 버전으로 간주하지 않는다.
- 서버는 가입 계속 자격, 닉네임 형식·전역 중복과 필수 약관의 식별자·버전을 최종 요청에서 다시 검증한다.
- 서버가 허용한 서비스 이용약관과 개인정보 수집·이용 동의의 버전과 서버 시각의 동의 시각은 별도 이력 테이블 없이 새 사용자 행에 각각 저장한다.
- 성공 시 `ACTIVE` 사용자 행을 처음 생성한다. 네이티브 endpoint는 로그인과 같은 세션 body를, 브라우저 endpoint는 Access Token body와 HttpOnly Refresh Token Cookie를 발급한다.
- 성공한 클라이언트의 다음 목적지는 `HOME`이다.
- 닉네임 중복 확인 이후 선점된 경우 `409 AUTH_010`으로 거절하며, 클라이언트는 가입 계속 자격과 다른 입력·동의 상태를 보존하고 닉네임 재확인을 요구한다.

## 로그인

`POST /api/v1/auth/sessions`

```json
{
  "email": "learner@example.com",
  "password": "<redacted>"
}
```

제안 세션 응답:

```json
{
  "success": true,
  "data": {
    "accessToken": "<redacted>",
    "accessExpiresAt": "2026-08-19T00:00:00Z",
    "refreshToken": "<redacted>",
    "refreshExpiresAt": "2026-08-19T00:00:00Z"
  },
  "error": null
}
```

- 이메일과 비밀번호의 어느 부분이 틀렸는지 구분하지 않고 `AUTH_001`을 반환한다.
- 미인증·정지·탈퇴 계정도 이메일 또는 비밀번호 불일치와 같은 `AUTH_001`을 반환한다.
- 성공 시 서버 형식 `<randomSessionId>.<256-bit random secret>`의 Refresh Token을 발급한다. 클라이언트는 opaque하게 취급하며, Redis에는 secret의 SHA-256 digest와 session/family 상태만 TTL로 저장한다.

## 인증 상태 갱신

`POST /api/v1/auth/sessions/refresh`

```json
{
  "refreshToken": "<redacted>"
}
```

- JSON body로 Refresh Token 하나를 보낸다.
- 성공 응답은 로그인과 같은 세션 모양을 사용한다.
- Redis Lua 또는 동등한 원자 연산으로 현재 digest 확인, 사용 digest tombstone 생성, 새 digest 교체를 한 번에 수행한다.
- 한 Refresh Token에 대한 동시 요청은 하나만 성공한다. 클라이언트는 갱신 요청을 직렬화해야 한다.
- 이미 사용된 digest가 TTL tombstone에서 발견되면 해당 session/family를 폐기하고 `AUTH_005`를 반환한다.

## 현재 세션 로그아웃

`DELETE /api/v1/auth/sessions/current`

요청:

```json
{
  "refreshToken": "<redacted>"
}
```

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

- Redis의 현재 session/family를 폐기하며 이미 만료·폐기됐어도 같은 성공 결과를 반환해 멱등적으로 처리한다.
- 응답 성공 여부와 관계없이 클라이언트는 로컬 Access/Refresh Token과 개인 화면 데이터를 제거한다.
- Access Token이 자체 포함 토큰이면 로그아웃 직후 서버 차단 수준을 별도 결정해야 한다. 최소한 Refresh Token 갱신은 불가능해야 한다.

## 브라우저 HttpOnly Cookie 세션 계약

이 절은 위의 body 계약을 대체하지 않는다. 서버는 웹 전용 세션 surface를 제공하며, 네이티브 소비자 폐기 계획이 별도로 승인될 때까지 두 계약을 병행한다.

### 브라우저 로그인

`POST /api/v1/auth/web/sessions`

요청 body는 기존 로그인과 같다.

```json
{
  "email": "learner@example.com",
  "password": "<redacted>"
}
```

성공 응답은 Refresh Token 원문을 포함하지 않는다.

```http
Set-Cookie: __Host-openmd_refresh=<redacted>; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=<remaining-seconds>
```

```json
{
  "success": true,
  "data": {
    "accessToken": "<redacted>",
    "accessExpiresAt": "2026-08-19T00:00:00Z",
    "refreshExpiresAt": "2026-09-18T00:00:00Z"
  },
  "error": null
}
```

- `refreshExpiresAt`은 비밀값이 아니므로 세션 만료 안내와 진단을 위해 body에 유지한다.
- 로그인 실패 응답은 기존 유효 Cookie를 덮어쓰거나 만료시키지 않는다.
- 이미 유효한 브라우저 세션 Cookie가 있는 상태에서 새 로그인이 성공하면 새 세션으로 교체한다. 교체 전 세션을 즉시 폐기할지는 세션 한도 정책과 함께 확정한다.

### 브라우저 인증 상태 갱신

`POST /api/v1/auth/web/sessions/refresh`

- 요청 body는 없다. 브라우저가 credentials를 포함한 요청에 Refresh Cookie를 자동 첨부한다.
- 서버는 Cookie에서 받은 opaque token에 기존 Redis 원자 회전과 재사용 탐지 규칙을 동일하게 적용한다.
- 성공하면 회전된 Refresh Token을 동일한 Cookie 이름·Domain·Path·보안 속성으로 덮어쓰고, body에는 새 Access Token과 만료 시각만 반환한다.
- Cookie가 없거나 잘못됐거나 만료됐거나 재사용된 경우 `401 AUTH_005`를 반환한다. 확정적으로 사용할 수 없는 자격이면 동일 속성의 만료 `Set-Cookie`도 반환한다.
- Redis나 서버의 일시 장애인 5xx에서는 Cookie를 만료시키지 않는다. 클라이언트는 자격 없음과 구분해 명시적으로 재시도할 수 있어야 한다.
- 회전은 멱등 요청이 아니므로 자동 무한 재시도하지 않는다. 응답 유실 시 같은 Cookie가 이미 소비됐을 가능성을 별도 정책으로 다룬다.

성공 body 모양은 브라우저 로그인 응답과 같다.

### 브라우저 현재 세션 로그아웃

`DELETE /api/v1/auth/web/sessions/current`

- 요청 body는 없다. Cookie가 유효하면 Redis의 현재 session/family를 폐기한다.
- Cookie가 없거나 이미 만료·폐기된 경우도 `200`으로 처리해 현재 세션 로그아웃을 멱등적으로 만든다.
- 서버는 발급 때와 동일한 Cookie 이름·Domain·Path를 사용하고 `Max-Age=0`으로 Cookie를 만료시킨다.
- 서버 응답 성공 여부와 관계없이 웹은 메모리 Access Token과 개인 cache를 제거한다.
- 기존 5분 Access Token을 로그아웃 즉시 차단할지는 별도 열린 질문이며, 최소 보장은 Refresh Token으로 새 Access Token을 발급받을 수 없다는 것이다.

### Cookie 속성

| 속성 | 제안 | 근거와 조건 |
| --- | --- | --- |
| 이름 | 운영 `__Host-openmd_refresh` | `__Host-` 규칙으로 `Secure`, host-only, `Path=/`을 브라우저가 강제한다. 로컬 HTTP 예외는 별도 이름을 사용한다. |
| `HttpOnly` | 항상 `true` | JavaScript 읽기와 직렬화를 막는다. |
| `Secure` | 운영·공유 환경 `true` | HTTPS에서만 전송한다. HTTP localhost는 로컬 전용 이름과 `false`를 허용하는 개발 예외를 명시한다. |
| `SameSite` | same-site 배포면 `Lax` 권장 | 웹과 API의 실제 site가 다르면 `None; Secure`가 필요하다. 운영 토폴로지 확인 전 하나로 확정하지 않는다. |
| `Domain` | 생략 | host-only로 제한한다. 운영 `__Host-` Cookie에는 `Domain`을 설정할 수 없다. |
| `Path` | 운영 `__Host-` 사용 시 `/` | Cookie는 더 넓게 전송되지만 서버는 웹 세션 endpoint에서만 읽는다. 좁은 Path가 더 중요하면 `__Secure-` 이름으로 바꾸는 선택을 별도 검토한다. |
| `Max-Age` | Redis 세션의 남은 절대 수명 | 회전할 때 최초 세션의 절대 만료를 연장하지 않는다. 서버 시각을 기준으로 계산한다. |

- 운영과 로컬 Cookie 이름·속성은 환경설정으로 명시하고, 발급·회전·삭제가 반드시 같은 설정 객체를 사용해야 한다.
- Refresh Token 원문과 전체 `Set-Cookie` 값은 애플리케이션 로그, 프록시 로그, 오류 추적 payload와 OpenAPI 예제에 남기지 않는다.
- Cookie의 절대 수명은 Redis session/family와 tombstone 수명을 넘지 않는다.

### CORS와 CSRF

브라우저 cookie 요청은 현재 `allowCredentials(false)`와 `/api/v1/auth/**` 전체 CSRF 제외 설정을 그대로 사용할 수 없다.

- 브라우저 허용 origin은 환경별 정확한 scheme·host·port 목록으로 제한하고 와일드카드를 허용하지 않는다.
- 웹 세션 endpoint에는 credentialed CORS를 허용하고, 브라우저 클라이언트는 credentials를 포함한다.
- `POST`/`DELETE` 웹 세션 요청에는 `X-OpenMD-CSRF: 1` 고정 custom header를 필수로 요구한다. 이 값은 비밀 토큰이 아니라 preflight를 강제하는 신호다.
- 서버는 위 header만 믿지 않고 `Origin`을 정확한 허용 목록과 비교한다. `Origin`이 없거나 허용되지 않은 브라우저 세션 요청은 세션 서비스 호출 전에 거절한다.
- `SameSite`는 보조 방어이며 CSRF 검증을 대체하지 않는다. 특히 `SameSite=None`이 필요한 cross-site 배포에서는 custom header·preflight·정확한 Origin 검증이 필수다.
- 가입·이메일 인증처럼 Cookie를 인증 근거로 사용하지 않는 기존 endpoint와 네이티브 body surface에는 브라우저 Cookie 인증을 섞지 않는다.
- 현재 `/api/v1/auth/**` 전체에 적용된 CSRF 제외 정책을 웹 세션 endpoint에 그대로 적용하지 않는다. 구체적인 서버 구현 방식은 서버 TRD가 책임진다.

### RTR와 다중 탭 경쟁

- Redis의 current digest 확인, 이전 digest tombstone 생성, 새 digest 교체와 재사용 시 session/family 폐기 규칙은 유지한다.
- 한 브라우저 탭 안에서는 refresh를 single-flight로 합친다.
- 여러 탭은 Cookie jar를 공유하므로 탭별 single-flight만으로 충분하지 않다. 웹은 같은 origin의 탭 사이 refresh 호출을 직렬화하는 조정 수단을 사용해야 한다.
- 서버는 클라이언트 조정을 보안 근거로 신뢰하지 않는다. 동일한 이전 Cookie가 실제로 동시에 도착하면 현재 RTR 규칙대로 하나만 성공하고 재사용 감지 시 family를 폐기한다.
- 위 fail-closed 정책은 보안은 유지하지만 탭 경합이나 성공 응답 유실도 재사용으로 판정해 전 탭 재로그인을 유발할 수 있다. 짧은 grace window나 회전 결과 재전달은 Refresh Token 원문 비저장 원칙과 공격 탐지 강도를 바꾸므로 이번 제안에서 확정하지 않는다.

### 오류와 세션 복구

| 조건 | 서버 결과 | 브라우저 복구 |
| --- | --- | --- |
| Cookie 없음·만료·잘못됨 | `401 AUTH_005`, 만료 Cookie | 메모리·개인 cache 제거 후 로그인 |
| 회전 토큰 재사용 탐지 | family 폐기, `401 AUTH_005`, 만료 Cookie | 모든 탭에서 세션 종료 후 로그인 |
| refresh 일시적 네트워크·5xx | Cookie 변경 없음, 공통 5xx envelope | 익명으로 단정하지 않고 재시도 |
| refresh 성공 응답 유실 | 기존 Cookie 소비 여부 불명 | 자동 무한 재시도 금지; 정책 확정 전 재로그인이 안전한 복구 |
| logout Cookie 없음·이미 폐기 | `200`, 만료 Cookie | 로컬 정리 후 공개 화면 |
| CSRF/Origin 검증 실패 | 서비스 호출 전 `403` | 자동 refresh하지 않고 요청 구성·origin 확인 |

Origin/CSRF 검증 실패는 `403 AUTH_009`를 사용한다. 클라이언트는 이를 `AUTH_005`로 오인해 refresh loop를 만들지 않아야 한다.

## 현재 사용자

`GET /api/v1/users/me`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "learner@example.com",
    "nickname": "공부왕7",
    "emailVerified": true,
    "status": "ACTIVE"
  },
  "error": null
}
```

- `nickname`은 가입이 완료된 활성 사용자의 현재 표시 닉네임이다.
- 향후 게임화 필드는 별도 기능이 확정될 때 추가한다.
- 내부 비밀번호·로그인 제공자 subject·세션 정보는 반환하지 않는다.

## 오류 응답

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AUTH_001",
    "message": "이메일 또는 비밀번호를 확인해 주세요.",
    "fields": []
  }
}
```

| 조건 | HTTP 상태 | 안정적인 오류 코드 | 사용자 복구 |
| --- | --- | --- | --- |
| 필드 형식·비밀번호 정책 위반 | `400` | 기존 `COMMON_001` | 필드 수정 |
| 가입 계속 자격 형식 위반 | `400` | 기존 `COMMON_001` | 이메일 인증부터 다시 시작 |
| 가입 계속 자격 없음·만료·사용됨 | `401` | 기존 `AUTH_005` | 이메일 인증부터 다시 시작 |
| 이메일 인증 코드 형식·값 오류 또는 사용됨 | `400` | `AUTH_003` | 남은 횟수 내 재입력 또는 새 코드 요청 |
| 이메일 인증 코드 만료·시도 소진 | `410` | `AUTH_004` | 새 코드 요청 |
| 닉네임 형식 위반·필수 약관 누락 | `400` | 기존 `COMMON_001` | 표시된 필드 수정 |
| 닉네임 중복 또는 최종 제출 전 선점 | `409` | `AUTH_010` | 닉네임 변경 후 중복 재확인 |
| 이메일/비밀번호 불일치 또는 비활성 인증 | `401` | `AUTH_001` | 재입력 |
| 접근·갱신 자격 없음/잘못됨/만료 | `401` | `AUTH_005` | 갱신 또는 재로그인 |
| 인증 메일 전달 실패 | `503` | `AUTH_008` | 잠시 후 가입 또는 재발송 재시도 |

초기 구현은 계정 상태를 구분하는 오류를 공개하지 않고 로그인 실패를 `AUTH_001`로 통일한다.

## 보안과 개인정보

- 비밀번호 해시는 Argon2id를 사용한다.
- 6자리 코드는 `SecureRandom`으로 만들고 Redis에는 서버 비밀키 기반 `HMAC-SHA-256("EMAIL_VERIFICATION:" + emailKey + ":" + code)` 또는 동등하게 정규화 이메일의 keyed digest와 purpose로 domain separation된 digest만 저장한다.
- `signUpToken`은 Java의 `UUID.randomUUID()`로 만들고 Redis key에는 원문 대신 SHA-256 digest를 사용한다. 원문과 digest를 인증·세션 로그에 남기지 않는다.
- Refresh Token의 session ID와 secret은 충분히 무작위로 만들고 Redis에는 secret의 SHA-256 digest만 저장한다. session ID는 조회 경로일 뿐 신뢰하지 않는다.
- 재발송 60초 제한 외의 IP·기기 단위 요청 제한은 운영 정책 확정 후 추가한다. 이메일을 제한 로그 키로 남겨서는 안 된다.
- 인증 성공/실패 로그에는 요청 추적 ID, 결과 코드와 필요한 최소 메타데이터만 남기고 이메일·비밀번호·토큰을 마스킹 또는 제외한다.
- 허용 origin은 `OPENMD_CORS_ALLOWED_ORIGINS`로 설정하며 여러 origin은 쉼표로 구분한다. 기본 개발 origin은 `http://localhost:5173`이고 운영에서는 명시적으로 덮어쓴다.
- 네이티브 body surface는 credentialed CORS를 사용하지 않는다. 구현된 브라우저 Cookie surface는 승인된 정확한 Origin의 credentialed CORS와 `X-OpenMD-CSRF` 검증을 함께 적용한다.
- 네이티브 앱의 Refresh Token은 일반 로컬 저장소가 아니라 OS 보안 저장소에 보관한다.
- 이메일 인증 코드와 Access/Refresh Token 원문을 로그, 분석 사건, Redis key/value에 남기지 않는다.
- 가입 계속 자격 원문도 토큰과 동일하게 URL, 영속 클라이언트 저장소, DB, 로그와 분석 사건에 남기지 않는다. 웹 클라이언트는 메모리에서만 보관한다.

## 재시도와 중복 요청

- 인증 메일 요청은 사용자 행을 만들지 않는다. 가입 완료는 정규화 이메일과 닉네임의 unique 제약을 기준으로 사용자를 중복 생성하지 않는다.
- 인증 메일 재발송은 요청 한도 안에서 새 코드를 만들고 Redis의 이전 코드를 무효화한다.
- 이메일 인증 완료와 로그아웃은 네트워크 응답 유실 뒤 재시도해도 계정·세션 상태를 중복 전이하지 않는다.
- 최종 가입 응답 유실 시 브라우저는 Cookie 도착 여부를 확인하기 위해 refresh를 한 번 호출한다. 성공하면 복구된 세션을 사용하고, `401 AUTH_005`이면 가입 완료를 반복하지 않고 로그인한다. 네이티브는 세션 body를 잃으면 로그인으로 복구한다.
- 갱신은 회전 때문에 일반적인 멱등 요청이 아니다. 소비자는 동시에 하나만 호출하고 실패 시 이전 자격을 무한 재시도하지 않는다.

## 단계적 전환과 호환성 — 현재 상태

1. 기존 네이티브 body endpoint와 DTO를 유지한 채 웹 전용 세션 endpoint, Cookie 설정과 계약 테스트를 추가했다.
2. 서버의 Cookie 발급·회전·삭제, 정확한 Origin, credentialed CORS와 CSRF 검증을 확인한 뒤 웹 클라이언트를 브라우저 surface로 전환했다.
3. 브라우저 응답에는 Refresh Token body를 반환하지 않고, 같은 요청에서 body와 Cookie를 동시에 인증 근거로 받아들이는 호환 모드를 두지 않는다.
4. 로그인·refresh·logout 성공률, `AUTH_005`, 재사용 탐지와 Origin/CSRF 거절은 토큰 원문 없이 관찰한다.
5. 네이티브 앱과 WebView 호출 경계가 확정되고 모든 소비자 마이그레이션이 끝난 뒤에만 기존 body surface의 폐기 여부를 별도 승인한다.

롤백은 웹이 기존 body endpoint로 자동 fallback하는 방식이 아니다. 서버와 웹 배포를 이전 호환 버전으로 함께 되돌리되, 이미 발급된 Cookie와 Redis session은 만료·폐기 정책에 따라 정리한다. 자동 fallback은 Refresh Token을 다시 JavaScript에 노출해 전환 목적을 훼손한다.

## 브라우저 인증 운영 기준

- 브라우저 로그인·refresh 성공 응답의 JSON, OpenAPI schema와 프론트 애플리케이션 상태 어디에도 Refresh Token 필드나 원문이 없다.
- 로그인 성공 Cookie는 승인된 이름, `HttpOnly`, 환경에 맞는 `Secure`·`SameSite`, host-only, 승인된 Path와 Redis 절대 만료에 맞는 수명을 가진다.
- refresh 요청은 body 없이 Cookie로 인증되고, 성공할 때마다 Redis current digest와 브라우저 Cookie가 같은 새 토큰 세대로 회전한다.
- 회전 전 Cookie의 재사용은 기존 정책대로 session/family를 폐기하고 안정적인 `AUTH_005`를 반환한다.
- logout은 Cookie나 Redis 세션이 이미 없어도 성공하며, 응답은 발급과 동일한 identity 속성으로 Cookie를 만료시킨다.
- 허용되지 않은 Origin 또는 필수 CSRF header가 없는 웹 세션 변경 요청은 Redis 세션을 읽거나 회전하기 전에 거절된다.
- credentialed CORS는 승인된 정확한 Origin에만 응답하며 와일드카드 Origin과 함께 사용되지 않는다.
- 5xx나 네트워크 오류는 자격 없음으로 변환되지 않고 유효할 수 있는 Cookie를 서버가 삭제하지 않는다.
- 네이티브 body endpoint는 웹 전환 동안 기존 요청·응답과 RTR 동작을 유지하며, 브라우저 endpoint와 토큰 전달 방식이 섞이지 않는다.
- WebView가 웹 cookie surface를 사용할 때 JavaScript나 native bridge로 Refresh Token 원문을 전달하지 않는다.
- 동일 Cookie의 동시 refresh, 성공 응답 유실, 회전 후 이전 Cookie 재사용과 다중 탭 세션 종료 시나리오를 통합 테스트로 재현해 승인된 fail-closed 결과를 확인한다.

## 열린 질문

- Refresh Token의 최종 절대 수명과 즉시 로그아웃을 위한 Access Token 차단 수준
- 운영 웹과 API의 실제 site 관계. same-site이면 `SameSite=Lax`를 권장하고, cross-site이면 `SameSite=None; Secure` 및 더 강한 CSRF 검증이 필요하다.
- 로컬 개발을 HTTPS로 통일해 운영과 같은 `__Host-` Cookie 이름을 쓸지, HTTP localhost 전용 이름과 `Secure=false` 예외를 둘지
- 기존 Cookie가 있는 상태에서 다른 계정 로그인에 성공할 때 이전 session/family를 즉시 폐기할지
- 네이티브 앱과 WebView의 실제 호출 경계를 언제 확정하고 기존 body surface를 언제 폐기할지
- 메일 발송을 비동기로 처리할 때 가입·재발송 상태 조회 API가 필요한지
- 모든 기기 로그아웃 API의 초기 포함 여부

## 변경 이력

| 날짜 | 변경 | 결정자 |
| --- | --- | --- |
| 2026-08-21 | 최종 가입 시 ACTIVE 사용자 생성, UUID v4 가입 자격, 단일 닉네임 컬럼과 사용자 행 필수 동의 저장으로 목표 계약 갱신 | 사용자 요청 |
