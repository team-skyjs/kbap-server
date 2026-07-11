# Research: 온보딩 — 기피 음식·국가·앱 언어 설정 + 완료 처리

**Date**: 2026-07-12 | **Plan**: [plan.md](plan.md)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 선택지가 여럿인 지점의 결정 기록이다.

## R1. 요청 인증 주입 방식 — HandlerMethodArgumentResolver

- **Decision**: app:api 에 `@AuthMemberId` 파라미터 애너테이션 + `HandlerMethodArgumentResolver` 를 신설한다. `Authorization: Bearer <access>` 헤더를 꺼내 기존 `TokenParser.parseAccessToken(token): Long`(application:client, KB-118)으로 검증·회원 PK 를 얻어 컨트롤러 파라미터로 주입한다. 헤더 부재·형식 오류·서명 불일치 = `AuthException(INVALID_ACCESS_TOKEN)`, 만료 = `AuthException(EXPIRED_ACCESS_TOKEN)` — 둘 다 기존 401 에러코드라 `GlobalExceptionHandler` 의 `MeogoException` 핸들러가 BaseResponse 401 로 변환한다(신규 예외 처리 코드 0).
- **Rationale**: KB-118 은 토큰 발급·재발급만 구현했고 수신 요청 검증 장치가 없다(`parseAccessToken` 호출처 0). 보호 엔드포인트가 KB-104 가 최초이므로 최소 장치가 필요하다. ArgumentResolver 는 (1) 컨트롤러 시그니처에 인증 요구가 명시적으로 드러나고, (2) 파라미터가 없는 엔드포인트(기존 food·scan·auth)는 전혀 영향받지 않으며, (3) Spring Web 만으로 구현돼 신규 의존이 없다.
- **Alternatives considered**:
  - **Spring Security 필터 체인**: 표준이지만 스타터 추가 + 전 엔드포인트 기본 잠금 → 기존 공개 API(food·scan·auth) 전부에 permitAll 설정 필요. 보호 엔드포인트 2개에 과함. 보호 API 가 늘어나 경로 기반 일괄 차단이 필요해지는 시점에 도입한다.
  - **HandlerInterceptor + ThreadLocal/request attribute**: 경로 패턴 관리가 별도 설정으로 분산되고 컨트롤러에서 인증 여부가 안 보인다. resolver 보다 이점 없음.

## R2. 엔드포인트 경로 — `POST /api/v1/members/me/onboarding` · `GET /api/v1/members/me`

- **Decision**: 제출 = `POST {ApiPaths.V1}/members/me/onboarding`, 조회 = `GET {ApiPaths.V1}/members/me`. `me` 는 토큰의 회원 PK 로 해석(경로 변수에 회원 id 를 받지 않음).
- **Rationale**: 리소스 = 회원 자신. 홈화면 진입 조회(DoD "온보딩 프로필·상태 응답")는 내 프로필 조회 `GET /members/me` 가 자연스럽고, 후속 프로필 수정(PUT/PATCH /members/me/profile)과 확장 방향이 일관된다. 경로 변수 회원 id 는 타인 프로필 접근 검증이 필요해져 배제.
- **Alternatives considered**: `POST /onboarding`(리소스 소속 불명), `PATCH /members/me`(온보딩 완료 전이라는 1회성 의미가 흐려짐 — 후속 프로필 수정과 구분 불가).

## R3. 검증 배치 — 카탈로그 멤버십은 application, 형식·규격은 도메인

- **Decision**: 검증을 두 층으로 나눈다.
  - **application(`CompleteOnboardingUseCase`)**: (a) 기피 성분 코드가 `AvoidanceSubstanceCode` enum(81종) 집합에 전부 포함되는지, (b) 국가 코드가 `CountryCode` 에 존재하는지(`CountryCode.from` null 검사), (c) 앱 언어가 `LanguageCode` 10종의 code 와 정확 일치하는지, (d) 닉네임이 trim 후 비어 있지 않은지. 위반 시 `OnboardingErrorCode`(400) 로 거절 — 저장 호출 전이므로 상태 불변이 자동 보장된다.
  - **도메인(기존)**: `AvoidanceSubstanceCodeRef` 형식(대문자·숫자·언더스코어), `MemberProfile` 불변식, `Member.completeOnboarding` 의 재완료 거부(`ONBOARDING_ALREADY_COMPLETED` 400).
- **Rationale**: 헌법 II — member 도메인은 avoidance 의 enum 을 import 할 수 없고 코드 문자열로만 참조한다. 두 컨텍스트를 아는 곳은 조합 계층인 application 뿐이므로 카탈로그 대조는 거기서 한다(식별자 enum 의 용도 그 자체 — 헌법 V "타 컨텍스트의 타입 안전 참조"). DB 조회 없이 컴파일타임 enum 으로 검증되므로 추가 쿼리 0.
- **Alternatives considered**: 카탈로그를 DB(`AvoidanceSubstanceRepository`)로 조회해 검증 — 시드 정합 테스트가 enum=DB 를 이미 보장하므로 불필요한 쿼리. Bean Validation(@Valid)만으로 처리 — 목록 멤버십·컨텍스트 교차 검증을 표현하기 어렵고 에러 메시지 규약(~습니다)과 어긋남(형식 수준 null 검증만 @Valid 활용).

## R4. 검증 에러코드 — application:client 에 `OnboardingErrorCode` 신설

- **Decision**: `com.meogo.application.client.member.OnboardingErrorCode`(kernel `ErrorCode` 구현, 전부 400): `INVALID_AVOIDANCE_SUBSTANCE_CODE`, `INVALID_COUNTRY_CODE`, `UNSUPPORTED_APP_LANGUAGE`, `INVALID_NICKNAME`. 메시지는 ~습니다 체. 재완료 거부는 기존 `MemberErrorCode.ONBOARDING_ALREADY_COMPLETED`(400), 회원 부재는 `MEMBER_NOT_FOUND`(404) 재사용.
- **Rationale**: 검증 규칙이 application 계층 소유(R3)이므로 에러코드도 같은 곳에 둔다 — KB-118 의 `AuthErrorCode` 와 동일 패턴. 도메인 `MemberErrorCode` 에 넣으면 avoidance 카탈로그 지식이 도메인 에러로 새는 모양이 된다.
- **Alternatives considered**: `MemberErrorCode` 확장(위 이유로 기각), `IllegalArgumentException` 의존(메시지·코드 관리가 흩어지고 GlobalExceptionHandler 의 범용 400 경로에 섞임).

## R5. 저장 모델 — 기존 스키마 재사용 (칼럼 rename 1건은 R8)

- **Decision**: 기존 `member` 테이블만 사용한다: `nickname` VARCHAR(30) 칼럼 + `profile` JSON 칼럼(`MemberProfileJson` — avoidanceSubstanceCodes·spicinessPreference·countryCode·appLanguage) + 온보딩 boolean 칼럼(R8 에 따라 `onboarding_completed` 로 rename). 저장은 기존 `MemberRepository.save`(어댑터 `applyProfile`) 경로 재사용.
- **Rationale**: KB-117 스키마 통합이 온보딩 프로필 저장을 이미 설계에 포함했다(`Member.updateProfile`·`completeOnboarding`·`MemberProfileJson` 이 모두 존재). 신규 칼럼·테이블이 필요 없고, 마이그레이션은 R8 의 네이밍 rename 1건뿐이다.
- **Alternatives considered**: 기피 성분 별도 조인 테이블 — 성분별 역조회(어떤 회원이 EGG 를 기피하나) 요구가 생기면 그때 정규화한다. 현재 소비 패턴은 회원 단건 로드뿐이라 JSON 이 충분(기존 결정 유지).

## R6. 온보딩 제출의 원자성 — 유스케이스 단일 트랜잭션

- **Decision**: `CompleteOnboardingUseCase` 를 `@Transactional` 로 묶고 흐름은 조회→검증→`updateProfile`→`completeOnboarding`→save 1회. 검증 실패·재완료 거부는 저장 호출 전에 예외로 이탈한다.
- **Rationale**: FR-007(저장·전이 원자성)·FR-009(실패 시 무변경)를 트랜잭션 + 저장 전 검증 순서로 충족. 외부 호출(LLM 등)이 없어 트랜잭션 내 장기 작업 금지 제약과도 무관.
- **Alternatives considered**: 없음(단일 애그리거트 단건 저장 — 자명).

## R7. 맵기 선호도 — 온보딩 입력에서 제외

- **Decision**: 온보딩 입력은 Jira KB-104 명세 그대로 닉네임·기피 성분·국가·앱 언어 4개. `spicinessPreference` 는 `MemberProfile` 기본값 5 를 유지한다(제출 시 기존 프로필의 값을 보존).
- **Rationale**: 이슈 입력 목록에 없음. 조정은 후속 프로필 수정 기능의 몫(spec Assumptions).
- **Alternatives considered**: 온보딩에 포함 — 스코프 확대라 기각(필요 시 후속 이슈에서 요청 필드 추가로 무파괴 확장 가능).

## R8. 온보딩 진행 표현 — 전 계층 `onboardingCompleted` boolean 통일 (사용자 결정 2026-07-12)

- **Decision**: 온보딩 진행 상태를 전 계층에서 `onboardingCompleted: Boolean` / `onboarding_completed` 로 통일한다. 도메인 `Member` 는 enum `OnboardingStatus`(PENDING/COMPLETED) 대신 boolean 프로퍼티를 갖고(enum 파일 삭제), `completeOnboarding()` 은 이미 true 면 `ONBOARDING_ALREADY_COMPLETED`(400) 를 던지는 행위를 유지한다. DB 칼럼은 신규 Flyway 마이그레이션으로 `onboarding_status` → `onboarding_completed` rename(기존 적용된 마이그레이션 파일은 무수정 — checksum 보호). 엔티티 `@Column` 도 동일하게 맞춰 enum↔boolean 왕복 변환(`if (onboardingCompleted) COMPLETED else PENDING`)을 제거한다.
- **Rationale**: 현재 같은 개념이 계층마다 다르게 표현된다 — 도메인 enum, 엔티티 boolean 필드 `onboardingCompleted`, 칼럼 `onboarding_status`(이름은 상태값을 암시하나 실제는 boolean), 응답 boolean. 값이 2개뿐인 enum 은 boolean 대비 정보가 없고 변환 코드만 남긴다. 기준 이름은 이미 존재하는 엔티티 필드명(`onboardingCompleted`)이라 변경 범위가 최소다. `onboarding_status` 라는 칼럼명은 "완료했는가?" 라는 실제 의미를 드러내지 못한다.
- **Alternatives considered**: enum 유지 + 칼럼만 rename — 왕복 변환·표현 불일치가 남아 기각. `has_completed_onboarding` — 기존 필드명과 어긋나고 길이만 늘어 기각. 온보딩 중간 단계 상태가 미래에 생길 가능성 — 생기면 그때 enum 재도입(YAGNI, 현재 요구는 완료 여부 이진값뿐).
- **검증 경로**: app:api 통합 테스트가 Testcontainers MySQL 에서 운영과 동일한 Flyway 마이그레이션을 실행하고 `ddl-auto=validate` 로 엔티티↔스키마 정합을 검증하므로(KB-46), rename 마이그레이션·`@Column` 불일치는 테스트에서 즉시 잡힌다.
- **파급 파일**: `Member.kt`·`OnboardingStatus.kt`(삭제)·`MemberTest.kt`(core:member), `LoginUseCaseTest.kt`(application:client), `MemberJpaEntity.kt`·`MemberRepositoryAdapterTest.kt`(infra:persistence), `AuthControllerTest.kt`(app:api — `onboarding_status` 칼럼명 문자열 assert 포함), 신규 마이그레이션 1건.
