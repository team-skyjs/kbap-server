# Implementation Plan: E2E 시나리오 테스트 도입 — 핵심 사용자 여정 4종 인수 테스트

**Branch**: `kb-167-e2e-scenario-tests` | **Date**: 2026-07-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-167-e2e-scenario-tests/spec.md`

## Summary

핵심 사용자 여정 4종(해피패스·인증 생명주기·메뉴판 스캔·탈퇴)을 `:app:api` 테스트 소스의 `scenario/` 패키지에 Kotest `BehaviorSpec` 인수 테스트로 추가한다. 실행 경계는 MockMvc 인프로세스, 데이터 저장소는 기존 Testcontainers(MySQL·Redis) 실물, 외부 시스템(소셜 인증·vision·S3)은 기존 seam 페이크를 재사용한다. **시나리오 본문은 한국어 스텝 메서드를 조립해 여정 서사로 읽히게 한다** — 예: `회원가입한다()` → `온보딩한다()` → `스캔한다()`. 스텝 메서드는 `ScenarioApiDriver` 헬퍼 하나가 제공하며, MockMvc 호출·응답 파싱은 전부 드라이버 안에 감춘다. 프로덕션 코드 변경 0줄 — 테스트 소스만 추가한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 `kbap.kotlin-common` 컨벤션 그대로)

**Primary Dependencies**: Spring Boot 4.1 `spring-boot-starter-test`, Kotest(BehaviorSpec + `kotest-extensions-spring`), MockMvc, Testcontainers(MySQL 8.4·Redis 8) — 전부 기존 의존, 신규 의존 0

**Storage**: MySQL Testcontainers(`MySqlContainerConfig`) + Redis Testcontainers(`RedisContainerConfig`) — `:core` testFixtures 재사용. Flyway on + `ddl-auto: validate`(테스트 yml 기존 설정)

**Testing**: Kotest `BehaviorSpec`, given/when/then 한국어(고정 컨벤션). 시나리오 스펙은 `@Tags("scenario")` 부착

**Target Platform**: `:app:api` 테스트 스위트(`./gradlew :app:api:test`)

**Project Type**: 백엔드 모듈러 모놀리스 — 이번 변경은 `app/api/src/test` 한정

**Performance Goals**: 별도 목표 없음(spec Assumptions). 태그로 선별/제외 가능하면 충분

**Constraints**: 프로덕션 코드·스키마·yml 무변경. 외부 네트워크 호출 0. 공유 마스터 데이터(기피물질 카탈로그) 삭제 금지 — 기존 `FoodTestSeed.clear()` 류의 전면 DELETE 를 시나리오에서 쓰지 않는다

**Scale/Scope**: 신규 테스트 파일 ~7개(시나리오 4 + 드라이버 1 + 시나리오용 소셜 페이크 1 + 시나리오 시드 1), 삭제 0

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 이 기능의 산출물 자체가 테스트다. 각 시나리오는 작성 → 실행으로 즉시 검증하며, 실패 시 그 지점이 곧 회귀 발견이다(신규 프로덕션 코드가 없으므로 Red→Green 사이클의 "구현"은 시나리오·드라이버 코드 자신) |
| II. Bounded Contexts | ✅ | 도메인 모듈 무변경. 테스트는 공개 API(HTTP)만 호출 — 도메인 경계를 가장 바깥에서 검증하는 형태 |
| III. Layered Dependency | ✅ | `app/api/src/test` 내부 추가만. 모듈 그래프 무변경 |
| IV. Persistence Encapsulation | ✅ | 엔티티·리포지토리 미참조. 데이터 준비는 기존 관행(raw JDBC 시드)을 따른다 — 도메인 서비스에 테스트용 create 를 뚫지 않는다 |
| V. Language Policy | ✅ | 콘텐츠 데이터 무변경. 시나리오가 lang 파라미터로 기존 폴백 동작을 소비만 한다 |

**Post-design re-check**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-167-e2e-scenario-tests/
├── plan.md              # This file
├── research.md          # Phase 0 — 조사 결과·결정 5건
├── data-model.md        # Phase 1 — 드라이버 스텝 메서드 계약·여정 상태
├── quickstart.md        # Phase 1 — 실행·선별 방법
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
app/api/src/test/kotlin/com/kbap/app/api/scenario/
├── ScenarioApiDriver.kt                    # 한국어 스텝 메서드 제공 — MockMvc·파싱 캡슐화 + 여정 상태(토큰·id) 보유
├── ScenarioSocialTokenVerifierConfig.kt    # @TestConfiguration @Primary — idToken 문자열에서 소셜 sub 파생(여정별 신규 계정)
├── ScenarioFoodSeed.kt                     # 시나리오 전용 음식 시드 — insert-if-absent, auto-increment id (카탈로그 삭제 금지)
├── HappyPathScenarioTest.kt                # P1: 가입→온보딩→홈→검색→상세→북마크
├── AuthLifecycleScenarioTest.kt            # P2: 로그인→만료(AUTH-004)→갱신→로그아웃→재로그인
├── MenuScanScenarioTest.kt                 # P3: 로그인→URL발급→업로드완료→스캔→위험도→최근스캔
└── WithdrawScenarioTest.kt                 # P4: 로그인→북마크→탈퇴→토큰무효→재가입 신규회원
```

**Structure Decision**: Jira 가 확정한 위치(`scenario/` 패키지, 여정당 1 스펙) 그대로. 기존 `e2e/MemberFoodJourneyTest.kt`(위험도 전이 여정)는 검증 초점이 달라(기피 설정↔위험도 변화) 유지하고 건드리지 않는다.

## 핵심 설계 결정 (research.md 요약)

1. **한국어 스텝 메서드 조립(사용자 지시)** — Kotlin 은 한국어 식별자를 백틱 없이 허용한다. `ScenarioApiDriver` 가 `회원가입한다()`·`온보딩한다()`·`음식을_검색한다()`·`스캔한다()` 류의 스텝 메서드를 제공하고, 시나리오 본문은 이들을 순서대로 조립한다. 드라이버 인스턴스가 여정 상태(accessToken·refreshToken·objectKey·foodId 등)를 필드로 보유해 스텝 간 전달(FR-002)을 자연스럽게 만든다.
2. **여정별 계정 격리 — 시나리오 전용 소셜 페이크** — 기존 `FakeSocialTokenVerifier`(AuthControllerTest 내부)는 고정 sub(`google-sub-fixed`)를 반환해 여정 간 회원이 겹친다. 시나리오 패키지에 **idToken 문자열을 sub 로 파생**하는 `@TestConfiguration @Primary` 페이크를 두고 각 시나리오가 `@Import` 한다 — 여정마다 고유 idToken(UUID 포함) 하나로 신규 계정 가입이 보장되고(FR-005), 테이블 청소가 필요 없다. `@Import` 는 스펙 단위라 기존 테스트와 충돌하지 않는다.
3. **만료 액세스 토큰 = 음수 TTL 발급(기존 선례)** — `JwtAuthenticationFilterTest` 의 관행 그대로 `JwtTokenIssuer(authTokenProperties.copy(accessTtl = 음수 Duration))` 로 서명은 유효하되 만료된 토큰을 생성한다. 시간 경과·sleep 없음.
4. **"스캔 히스토리 조회" 스텝은 홈의 recentScans 로 검증** — 전용 스캔 히스토리 GET 엔드포인트가 현재 없다(스캔 이력은 `GET /api/v1/home` 의 `recentScans` 로만 노출). Jira 여정 문구의 "스캔 히스토리 조회"는 이 API 로 매핑한다. 신규 엔드포인트 추가는 범위 밖(프로덕션 코드 0줄 제약).
5. **음식 시드 — insert-if-absent, 카탈로그 보존** — KB-163 이후 음식 시드는 0건이므로 시나리오가 자체 준비한다. 단 기존 `FoodTestSeed` 의 `clear()`(avoidance_substance 전면 DELETE — Flyway 카탈로그 81종 파괴)는 쓰지 않는다. 시나리오 시드는 (a) food 를 auto-increment 로 INSERT(고정 id 충돌 회피)하고 검색 API 응답에서 foodId 를 발견하며, (b) 참조하는 기피물질은 code 기준 insert-if-absent 로 존재만 보장한다(삭제 없음 — 읽기 전용 전제 유지, 다른 테스트가 카탈로그를 지운 순서에도 견딤).

## 여정 → API 매핑

| 여정 | 스텝 시퀀스 (전부 기존 엔드포인트) |
|------|------|
| 해피패스 | `POST /auth/login`(newMember=true) → `POST /members/me/onboarding` → `GET /home`(authenticated·avoidedSubstances 반영) → `GET /foods/search?keyword` → `GET /foods/{id}` → `POST /bookmarks` → `GET /bookmarks`(해당 음식 노출) |
| 인증 생명주기 | `POST /auth/login` → 만료 토큰으로 `GET /members/me/profile` = 401 `AUTH-004` → `POST /auth/refresh`(rotation) → 새 토큰으로 호출 성공 → `POST /auth/logout` → 구 refreshToken 으로 `POST /auth/refresh` = 거절 → `POST /auth/login` 재로그인 성공 |
| 메뉴판 스캔 | `POST /auth/login` + 온보딩 → `POST /images/upload-url` → (페이크 스토리지에 head 응답 주입) `POST /images/complete` → (vision 페이크 program) `POST /scans`(matched·riskLevel 확인) → `GET /home` 의 `recentScans` 에 노출 |
| 탈퇴 | `POST /auth/login` + 온보딩 → `POST /bookmarks` → `PATCH /auth/withdraw` → 구 accessToken 으로 회원 API 호출 실패 + 구 refreshToken 갱신 거절 → 같은 idToken 재로그인 = `newMember=true` + 북마크 0건 |

## 태그·실행

- 시나리오 스펙 4개에 `@Tags("scenario")`(`io.kotest.core.annotation.Tags`) 부착 — 기존 `arch` 태그와 동일 메커니즘, `buildSrc` 가 `-Dkotest.tags` 를 이미 전달한다(신규 빌드 설정 0).
- 선별: `./gradlew :app:api:test -Dkotest.tags="scenario"` / 제외: `-Dkotest.tags="!scenario"`.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
