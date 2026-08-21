# Product Design Index

이 디렉터리는 OpenMD의 제품 설계 원장과 구현 계약을 찾기 위한 시작점이다. 모든 문서를 한꺼번에 읽지 말고 아래 표에서 현재 작업에 필요한 문서만 선택한다.

## 문서 운영 방식

문서를 새로 만들거나 분리할지 판단할 때는 [문서 운영 가이드](documentation-guide.md)를 확인한다. OpenMD의 기본은 사용자 기능당 하나의 집중된 PRD이며, 실제 복잡성이 있을 때만 UX 화면 명세·Flow·TRD 중 필요한 동반 문서를 추가한다. 둘 이상의 애플리케이션이 합의할 입력·출력은 Contract로 분리한다.

## 읽기 순서

1. 제품의 목적이나 범위를 판단할 때는 [제품 개요](product/overview.md)와 [제품 원칙](product/principles.md)을 읽는다.
2. 사용자에게 보이는 동작을 만들 때는 관련 [기능명세](features/)와 [사용자 흐름](flows/)을 읽는다.
3. 화면을 설계하거나 구현할 때는 관련 `docs/screens/` 문서를 읽는다.
4. 클라이언트와 서버의 경계를 바꿀 때는 [API 계약](contracts/api/)과 [데이터 계약](contracts/data/)을 읽는다.
5. 과거 판단의 이유가 필요할 때는 [결정 기록](decisions/)을 읽는다.

## 현재 원장

| 관심사 | 원장 | 상태 |
| --- | --- | --- |
| 제품 목표와 단계별 범위 | [overview.md](product/overview.md) | 초안 |
| 제품 판단 원칙 | [principles.md](product/principles.md) | 초안 |
| 하단 탭과 전역 이동 | [navigation.md](product/navigation.md) | 초안 |
| 공통 용어 | [glossary.md](product/glossary.md) | 초안 |
| 홈 | [00-home.md](features/00-home.md) | 초안 |
| 자체 로그인 | [01-local-auth.md](features/01-local-auth.md) | 초안 |
| 사용자·인증 데이터 | [authentication.md](contracts/data/authentication.md) | 초안 |
| 인증 API | [authentication.md](contracts/api/authentication.md) | 초안 |
| 2단계 이메일 회원가입 서버 설계 | [two-step-signup.md](../server/docs/technical/two-step-signup.md) | 구현됨 |
| 브라우저 Refresh Token Cookie 서버 설계 | [browser-refresh-cookie.md](../server/docs/technical/browser-refresh-cookie.md) | 초안 |
| 서버 OpenAPI와 Swagger UI 운영 | [openapi-documentation.md](../server/docs/technical/openapi-documentation.md) | 초안 |
| 학습자료 만들기 | [02-content-import.md](features/02-content-import.md) | 검토 중 |
| 학습자료 생성 서버 설계 | [learning-material-creation.md](../server/docs/technical/learning-material-creation.md) | 검토 중 |
| 퀴즈 생성·풀이·결과·복습 | [03-quiz-generation.md](features/03-quiz-generation.md) | 검토 중 |
| 인증 흐름 | [authentication.md](flows/authentication.md) | 초안 |
| 학습자료 만들기 흐름 | [content-import.md](flows/content-import.md) | 검토 중 |
| 퀴즈 생성부터 복습까지의 흐름 | [quiz-solving.md](flows/quiz-solving.md) | 검토 중 |
| 학습자료·퀴즈·복습 API | [quiz-learning.md](contracts/api/quiz-learning.md) | 검토 중 |
| 웹 본 퀴즈 임시 상태·보존 | [quiz-solving.md](../web/docs/technical/quiz-solving.md) | 검토 중 |
| 앱 본 퀴즈 임시 상태·보존 | [quiz-solving.md](../app/docs/technical/quiz-solving.md) | 검토 중 |
| 홈 화면 | [home.md](screens/home.md) | 초안 |
| 학습 화면 | [learning.md](screens/learning.md) | 초안 |
| 프로필 화면 | [profile.md](screens/profile.md) | 초안 |
| 회원가입 화면 | [signup.md](screens/signup.md) | 초안 |

화면 문서는 기능명세의 규칙을 복제하지 않고 관련 원장을 링크한다.

## 문서 생성 기준

- 새 사용자 가치를 정의할 때: [기능명세 템플릿](templates/feature-spec.md)
- 화면의 구조·상태·행동을 정의할 때: [화면 명세 템플릿](templates/screen-spec.md)
- 여러 상태와 분기를 정의할 때: [흐름 템플릿](templates/flow-spec.md)
- 클라이언트와 서버의 합의를 정의할 때: [API 계약 템플릿](templates/api-contract.md)
- 작은 문구 수정이나 구현 내부 결정은 별도 제품 문서를 만들지 않는다.

## 현재 열린 제품 질문

- 첫 배포 대상이 Expo 앱, 모바일 웹뷰, 웹 중 어디까지인지
- Refresh Token의 최종 수명과 앱/WebView별 전달·보관 방식 (브라우저 HttpOnly Cookie 서버 계약과 Access Token 5분은 확정)
- Notion 인증 방식과 사용자가 페이지를 선택할 수 있는 권한 범위 (일회성 복사와 비동기화는 확정)
- 경험치·랭킹·친구·꾸미기 기능의 출시 순서와 운영 정책
