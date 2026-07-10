# Tasks: Firebase 토큰 검증 소셜 로그인 — 자체 JWT 쿠키 발급·재발급·로그아웃

**Input**: Design documents from `/specs/kb-118-firebase-jwt-auth/`

**Prerequisites**: plan.md, spec.md, research.md (R1~R8), data-model.md, contracts/auth-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 로직은 실패 테스트(Red — 컴파일 에러도 인정) 작성·확인 후 구현(Green). Kotest `BehaviorSpec`, given/when/then 한국어. **Firebase 실호출은 어떤 테스트에도 없다.**

**Organization**: US1 로그인(P1) → US2 재발급(P2) → US3 로그아웃(P3) — 우선순위 = 의존성 순서와 일치. 토큰 발급/파싱·예외 체계·Redis 저장소는 세 스토리 공유라 Foundational.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·의존 없음) / **[Story]**: US1(로그인)·US2(재발급)·US3(로그아웃)

## Path Conventions

`core/member`·`application/client`(auth 패키지 신설)·`infra/persistence`(auth 패키지 신설)·`app/api`. 모든 경로는 리포 루트 기준.

---

## Phase 1: Setup — 의존성·Redis 인프라 (R1·R4)

**Purpose**: 카탈로그·빌드·docker·설정 — 이후 모든 테스트의 전제

- [X] T001 `gradle/libs.versions.toml` 에 firebase-admin·jjwt(api/impl/jackson) 버전·좌표 추가, `application/client/build.gradle.kts` 에 firebase-admin·jjwt 의존성(impl/jackson 은 runtimeOnly)·`infra/persistence/build.gradle.kts` 에 `spring-boot-starter-data-redis`(implementation) 추가, 컴파일 확인 (`./gradlew compileKotlin` 계열)
- [X] T002 [P] `docker-compose.yml` 에 `meogo-redis`(redis:8, 6379) 서비스 추가 + `app/api/src/main/resources/application.yml` 계열에 `meogo.auth.jwt.{secret,access-ttl: 30m,refresh-ttl: 14d}`·`meogo.auth.firebase.credentials-path`·프로필별 `spring.data.redis.*` 설정(local=docker, test 는 Testcontainers 가 주입), `.env` 키 목록은 quickstart.md 기준
- [X] T003 [P] Redis Testcontainers 픽스처: `infra/persistence/src/testFixtures/kotlin/com/meogo/infra/persistence/testsupport/RedisContainerConfig.kt` — `GenericContainer("redis:8")` + `@ServiceConnection`(MySqlContainerConfig 와 동형)

---

## Phase 2: Foundational — 예외 체계·토큰 발급/파싱·Refresh 저장소

**Purpose**: 세 스토리가 전부 딛는 빌딩블록

**⚠️ CRITICAL**: 이 phase 완료 전에 스토리 시작 불가

- [X] T004 예외 체계: `application/client/src/main/kotlin/com/meogo/application/client/auth/AuthErrorCode.kt`(INVALID_SOCIAL_TOKEN·UNSUPPORTED_PROVIDER·INVALID_REFRESH_TOKEN·EXPIRED_REFRESH_TOKEN — 전부 401, R5 표)·`AuthException.kt`(MeogoException 하위) — 단순 선언이라 테스트는 소비처(T005~)에서 커버
- [X] T005 토큰 실패 테스트: `application/client/src/test/kotlin/com/meogo/application/client/auth/TokenTest.kt` — ① access 발급→파싱 시 memberId(sub) 복원·개인정보 클레임 부재 ② refresh 발급 시 jti 존재 ③ 조작 토큰(다른 키 서명·본문 변조) 파싱 → INVALID 계열 AuthException ④ 만료 토큰 파싱 → EXPIRED 계열 구분 ⑤ TTL 이 프로퍼티를 따름, Red 확인 (`./gradlew :application:client:test`)
- [X] T006 토큰 Green: `TokenIssuer.kt`·`TokenParser.kt`(jjwt HS256, `ExpiredJwtException` → EXPIRED 매핑)·`AuthTokenProperties.kt`(secret·access-ttl·refresh-ttl) 구현, T005 Green
- [X] T007 RefreshTokenStore port 선언: `core/member/src/main/kotlin/com/meogo/core/member/RefreshTokenStore.kt` — `save(jti, memberId, ttl)`/`findMemberId(jti): Long?`/`delete(jti)` (Spring-free 인터페이스)
- [X] T008 Redis 어댑터 실패 테스트: `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/auth/RefreshTokenRedisAdapterTest.kt` — save→findMemberId 복원, delete 후 null, 미저장 jti null, **save 직후 TTL 이 지정값 이하로 설정됨**(Redis Testcontainers), Red 확인
- [X] T009 Redis 어댑터 Green: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/auth/RefreshTokenRedisAdapter.kt` — `StringRedisTemplate`, 키 `auth:refresh:{jti}` → memberId, TTL 지정, T008 Green

**Checkpoint**: 토큰 발급/파싱·저장/폐기 빌딩블록 완성 — 스토리 시작 가능

---

## Phase 3: User Story 1 — 소셜 계정으로 로그인/가입 (Priority: P1) 🎯 MVP

**Goal**: Firebase 토큰 검증 → 신원 추출(원본 sub) → resolve(가입/로그인) → 토큰 2종 쿠키 발급 + newMember 플래그

**Independent Test**: 페이크 verifier 로 유스케이스 단위 검증 + MockMvc 로 로그인 엔드포인트·쿠키 검증 — 이 스토리만으로 인증 상태 획득 완결

### Tests for User Story 1 (Test-First — Red 확인) ⚠️

- [X] T010 [US1] 클레임 매핑 실패 테스트: `application/client/src/test/kotlin/com/meogo/application/client/auth/FirebaseClaimMapperTest.kt` — ① google.com/apple.com → SocialProvider 매핑 ② **firebase.identities 의 원본 sub 추출(최상위 sub=Firebase uid 미사용 검증)** ③ email null·애플 릴레이 주소 그대로 보존 ④ 미지원 provider → UNSUPPORTED_PROVIDER ⑤ identities 에 해당 provider 원소 없음 → INVALID_SOCIAL_TOKEN, Red 확인
- [X] T011 [US1] 로그인 유스케이스 실패 테스트: `application/client/src/test/kotlin/com/meogo/application/client/auth/LoginUseCaseTest.kt` — 페이크 SocialTokenVerifier·인메모리 RefreshTokenStore·페이크 MemberRepository 로 ① 신규 가입 → newMember=true·토큰 2종·store 에 jti 저장 ② 기존 재로그인 → newMember=false·중복 회원 없음 ③ verifier 예외(검증 실패) → INVALID_SOCIAL_TOKEN 전파·회원 미생성·store 미저장, Red 확인

### Implementation for User Story 1

- [X] T012 [US1] 매핑·검증기 Green: `FirebaseClaimMapper.kt`(순수 함수) + `SocialTokenVerifier.kt`(interface seam) + `FirebaseTokenVerifier.kt`(firebase-admin `verifyIdToken` → 매퍼 호출, `@ConditionalOnProperty("meogo.auth.firebase.credentials-path")`) + `FirebaseAppConfig.kt`(서비스 계정 키 초기화), T010 Green
- [X] T013 [US1] LoginUseCase Green: verify → `MemberIdentityResolver.resolve` → TokenIssuer 2종 발급 → store.save(jti, TTL=refresh 수명) → `LoginResult(memberId, newMember, accessToken, refreshToken)`, T011 Green
- [X] T014 [US1] 로그인 엔드포인트 실패 테스트: `app/api/src/test/kotlin/com/meogo/app/api/auth/AuthControllerTest.kt` — MockMvc + `@TestConfiguration` 페이크 verifier 빈(MySQL·Redis Testcontainers) — ① POST /api/v1/auth/login 성공: BaseResponse{memberId,newMember} + Set-Cookie 2건(access_token Path=/·refresh_token Path=/api/v1/auth·HttpOnly·SameSite=Lax·Max-Age) ② idToken blank → 400 ③ 페이크 verifier 예외 → 401 fail 봉투, Red 확인
- [X] T015 [US1] 컨트롤러 Green: `app/api/src/main/kotlin/com/meogo/app/api/auth/` — `AuthController.kt`(+`AuthApi.kt` springdoc)·`LoginRequest.kt`(@NotBlank idToken)·`LoginResponse.kt`·`AuthCookieFactory.kt`(ResponseCookie, Secure 는 non-local 프로필), T014 Green
- [X] T016 [US1] 정지 회원 통합 검증: `AuthControllerTest.kt` 에 추가 — 가입 후 직접 SQL 로 member_status=SUSPENDED → 재로그인 401/409 (fail 봉투) + **member 행 수 불변**(신규 미생성 — FR-010, R6 기존 메커니즘), Red→Green(코드 변경 없이 통과해야 정상 — 실패 시 원인 조사)

**Checkpoint**: 로그인 완결 — P1 MVP. 여기서 멈추고 검증/커밋 가능

---

## Phase 4: User Story 2 — 토큰 재발급 rotation (Priority: P2)

**Goal**: refresh 로 access·refresh 둘 다 갱신(유효기간 연장·구 토큰 즉시 폐기), 만료 = 강제 로그아웃

**Independent Test**: 재발급 후 두 쿠키 갱신·구 refresh 재사용 401·만료 refresh 강제 로그아웃을 단위+MockMvc 로 검증

### Tests for User Story 2 (Test-First — Red 확인) ⚠️

- [X] T017 [US2] RefreshUseCase 실패 테스트: `application/client/src/test/kotlin/com/meogo/application/client/auth/RefreshUseCaseTest.kt` — 인메모리 store — ① 유효 refresh → 새 access+refresh 반환·구 jti 삭제·신 jti 저장(rotation) ② 구 jti 재사용 → INVALID_REFRESH_TOKEN ③ 만료 refresh → EXPIRED_REFRESH_TOKEN + store 의 jti 삭제 ④ 조작 refresh → INVALID_REFRESH_TOKEN ⑤ store 에 없는 유효 서명 refresh(위조/로그아웃) → INVALID_REFRESH_TOKEN, Red 확인
- [X] T018 [US2] 재발급 엔드포인트 실패 테스트: `AuthControllerTest.kt` 에 추가 — ① login→refresh: 200 + Set-Cookie 2건(둘 다 신규 값·refresh Max-Age 재설정) ② 직전 refresh 쿠키 재사용 → 401 ③ 쿠키 부재 → 401 ④ 만료 refresh(테스트용 짧은 TTL 발급) → 401 + **쿠키 2종 Max-Age=0**(강제 로그아웃), Red 확인

### Implementation for User Story 2

- [X] T019 [US2] RefreshUseCase Green + 컨트롤러 refresh 엔드포인트·강제 로그아웃 쿠키 처리(AuthCookieFactory 만료 쿠키) 구현, T017·T018 Green

**Checkpoint**: 세션 연장 흐름 완결

---

## Phase 5: User Story 3 — 로그아웃 (Priority: P3)

**Goal**: 서버 저장 refresh 폐기 + 쿠키 만료 — 이후 그 refresh 로 재발급 불가

**Independent Test**: login→logout→refresh 401 흐름을 MockMvc 로 검증

### Tests for User Story 3 (Test-First — Red 확인) ⚠️

- [X] T020 [US3] 로그아웃 실패 테스트: `LogoutUseCaseTest.kt`(인메모리 store — jti 삭제·무 refresh 멱등) + `AuthControllerTest.kt` 에 추가 — ① login→logout: 200 + 쿠키 2종 Max-Age=0 ② logout 후 그 refresh 로 재발급 → 401 ③ 쿠키 없이 logout → 200(멱등), Red 확인

### Implementation for User Story 3

- [X] T021 [US3] LogoutUseCase Green + 컨트롤러 logout 엔드포인트 구현, T020 Green

**Checkpoint**: 세 스토리 수용 시나리오 전부 통과

---

## Phase 6: Polish & Cross-Cutting

- [X] T022 전체 빌드 회귀: `./gradlew build` — ArchUnit `ModuleBoundaryTest` 포함 전 모듈 Green. app:batch 부팅 영향 없음 확인(application:client 미의존이라 무접촉 — 빌드로 검증). Kotlin 주석 금지·BehaviorSpec 규약 훑기
- [X] T023 quickstart.md 수동 스모크(로컬 docker redis + 실 Firebase 키 있으면 login 왕복, 없으면 부팅 안전만: 키 미설정 부팅 → verifier 빈 미생성 확인) + 태스크/논리 단위 커밋 정리

---

## Dependencies & Execution Order

```
Phase 1 (Setup: 의존성·redis 인프라)
  → Phase 2 (Foundational: 예외·토큰·store)   ← T005 가 T001, T008 이 T003 전제
    → Phase 3 (US1 로그인)                     ← LoginUseCase 가 TokenIssuer·store 사용
      → Phase 4 (US2 재발급)                   ← 로그인이 만든 세션 전제 + TokenParser
      → Phase 5 (US3 로그아웃)                 ← 동일. US2 와 서로 독립이나 같은 테스트 파일이라 순차
        → Phase 6 (Polish)
```

- MVP = Phase 1~3 (로그인). US2·US3 은 독립 증분.
- 병렬 기회: T002∥T003(다른 파일), Phase 2 내 T005~T006(application) ∥ T007~T009(core+persistence) — 모듈이 달라 병렬 가능. 스토리 phase 는 같은 파일(`AuthControllerTest.kt` 등) 순차 수정.

## Parallel Example

```bash
# Phase 1 후반 동시:
Task: "T002 docker-compose·yml 설정"
Task: "T003 RedisContainerConfig (testFixtures)"
# Phase 2 두 갈래 동시:
Task: "T005→T006 토큰 발급/파싱 (application/client)"
Task: "T007→T009 RefreshTokenStore port+Redis 어댑터 (core+persistence)"
```

## Implementation Strategy

1. Phase 1~2 — 빌딩블록(토큰·store) 확보
2. Phase 3(US1) — **로그인 MVP, 멈추고 검증/커밋 가능**
3. Phase 4(US2) → Phase 5(US3) — 재발급 rotation·로그아웃 증분
4. Phase 6 — 회귀·스모크
- 각 태스크/논리 단위 커밋(헌법 Workflow)

## Notes

- Red 는 반드시 실행해 실패 확인(컴파일 에러 인정). 이미 통과하는 테스트가 나오면(T016) 원인을 조사하고 기록
- Firebase 실호출 금지 — 서비스 계정 키 없이 전 테스트 통과해야 함
- `MemberIdentityResolver`·`MemberRepository`·기존 member 코드는 수정 금지
- 자체 JWT 사용자 클레임 = 회원 PK(sub) 단 하나 — 테스트가 개인정보 클레임 부재를 명시 검증(T005)
