# Research: 신규 음식 적재 관리자 API

**Date**: 2026-07-21 · Technical Context 에 NEEDS CLARIFICATION 없음 — 아래는 설계 결정 확정 기록.

## R1. 인가 가드 방식 — JWT 필터 재사용 + Admin 인터셉터

- **Decision**: `JwtAuthenticationFilter` 의 URL 패턴에 `/api/v1/admin/*` 를 추가해 서명 검증·`ROLE_ATTRIBUTE` 세팅을 재사용하고, `HandlerInterceptor`(`AdminAuthorizationInterceptor`) 를 `/api/v1/admin/**` 에 등록해 `ROLE_ATTRIBUTE != ADMIN` 이면 403 `ADMIN_FORBIDDEN`(AUTH-008) 을 던진다.
- **Rationale**: 필터가 이미 서명 검증(401)과 role 어트리뷰트 세팅을 다 한다(`JwtAuthenticationFilter.kt:30`). 남은 건 "role 이 ADMIN 인가" 한 판정뿐 — 인터셉터 한 클래스면 끝난다. 401(토큰 문제)/403(권한 부족) 구분도 자연히 계층별로 나뉜다.
- **Alternatives considered**:
  - Spring Security 도입 — 프로젝트에 없음. 의존 추가 + 전역 필터체인 재구성 비용이 가드 한 개 대비 과잉. 기각.
  - 필터 하나 더(AdminAuthorizationFilter) — 인터셉터와 동일 효과이나 BaseResponse JSON 수동 직렬화가 필요(필터는 예외 핸들러 밖). 인터셉터는 예외를 기존 `@RestControllerAdvice` 로 던져 재사용. 기각.
  - 컨트롤러 안 if 검사 — admin 엔드포인트가 늘 때마다 반복. 경로 프리픽스 가드가 근본 위치. 기각.

## R2. ADMIN 토큰 발급 — 기존 issuer 로 오프라인 발급, 발급 API 없음

- **Decision**: 관리자 로그인/발급 API 를 만들지 않는다. `JwtTokenIssuer.issueAccessToken(memberId = 0, role = MemberRole.ADMIN)` 을 대상 환경의 `kbap.auth.jwt.secret` 으로 실행해 토큰을 오프라인 발급한다(스니펫은 quickstart.md). subject 는 실존 member 가 아니어도 된다 — admin 엔드포인트는 `@AuthMemberId` 를 쓰지 않고 member 조회도 없다.
- **Rationale**: DoD "인증은 필요없음 — 인가만". 서명이 곧 발급 증명이므로(Clarify Q1) 발급 인프라가 0 이어도 성립. 표준 access TTL 적용(Clarify Q2) — 만료되면 같은 방법으로 재발급.
- **Alternatives considered**: 관리자 로그인 플로우 / 발급 전용 엔드포인트 — 스펙이 명시적으로 배제(Out of Scope). 무만료 토큰 — Clarify Q2 에서 기각(유출 시 폐기 불가).

## R3. 멱등 적재·카운트 — 기존 upsert 재사용, 카운트는 사전 조회 diff

- **Decision**: `FoodService` 에 `seedIncomplete(names: Set<String>): SeedIncompleteResult` 를 추가한다. 구현: ① `findByKoreanNameIn(names)` 로 기존 이름 조회 → `skipped` ② 나머지를 기존 `createIncomplete`(→ `upsertIncomplete` insert-or-ignore) 로 적재 → `created`. 반환 `SeedIncompleteResult(requested, created, skipped)`.
- **Rationale**: 멱등성(FR-005)·동시성(FR-008)은 이미 `ON DUPLICATE KEY UPDATE id = id` 가 보장한다(kb-90 에서 데드락·유령행까지 검증). 새로 필요한 것은 응답 카운트(FR-006)뿐이고, 그것은 upsert 앞의 SELECT diff 로 나온다. 도메인 쓰기 로직 신규 작성 없음.
- **동시성 카운트 주의**: 두 동시 요청이 같은 신규 이름을 넣으면 둘 다 `created` 로 셀 수 있다(행은 upsert 로 1개 보장 — 정합 훼손 없음, 카운트만 낙관적). 관리자 수동 도구에서 수용. `ponytail:` 주석으로 명시 예정.
- **Alternatives considered**: upsert 의 affected-rows 로 카운트 — MySQL `ON DUPLICATE KEY` 의 affected-rows 의미(1=insert, 2=update, 0=no-op)가 드라이버 설정(`useAffectedRows`)에 좌우돼 취약. 기각. `INSERT IGNORE` — 다른 unique 위반까지 삼킴. 기각(기존 결정 유지).

## R4. 요청 검증 — 요청 DTO 소유(헌법 V), blank 필터·dedup·길이 제한

- **Decision**: `AdminFoodSeedRequest(koreanNames: List<String>?)` 가 경계 검증을 소유한다: `@field:NotNull` + 각 항목 `@field:Size(max=255)`(= `KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH`, 위반 시 400 COMMON-002). `toKoreanNames()` 가 trim 후 blank 제거·dedup 해 `Set<String>` 확정값을 만든다. 유효 항목 0개면 빈 Set → 서비스는 0건 성공(FR-007, 스펙 엣지케이스).
- **Rationale**: 헌법 V — 외부 입력 판정은 요청 경계가 소유, 도메인은 확정값 수신. `Food.incomplete` 의 `require`(blank·길이) 는 도메인 불변 방어로 남되, DTO 가 먼저 걸러 500 경로를 차단한다.
- **Alternatives considered**: 서비스에서 검증 — 헌법 V 위반. 기각. 길이 초과 항목 조용히 드롭 — 오타·인코딩 깨짐을 삼켜 관리자 실수가 안 드러남. 400 으로 시끄럽게 실패가 관리자 도구에 맞음. 기각.

## R5. 센티널 초기값(FR-009) — kb-182 소유 경로 재사용, kb-186 은 미수정

- **Decision**: 적재 음식의 `spiciness=-1`·`avoidance_substances=NULL` 은 `kb-182-batch-pipeline-skeleton` 이 변경하는 공유 경로(`Food.incomplete()`·Flyway nullable) 산출물이다. kb-186 은 이 경로를 호출만 하고 수정하지 않는다. kb-182 머지 전에 kb-186 이 먼저 가면 센티널 assert 는 `@Ignore`(사유 주석) 로 잠근다.
- **발견한 갭 → kb-182 반영 확정(2026-07-21)**: `FoodJpaRepositoryCustomImpl.upsertIncomplete` SQL 이 `avoidance_substances='[]'` 를 하드코딩(엔티티 값 미바인딩)해 센티널을 무력화할 뻔했다. `'[]'`→`NULL` 변경(1-bis)과 마이그레이션 INCOMPLETE 백필의 `spiciness=0→-1` 일관성 보정(2-bis)이 kb-182 지시서에 추가됐다. spiciness 는 바인딩되어 있어 -1 이 그대로 흐른다.
- **Alternatives considered**: kb-186 이 직접 SQL 수정 — 같은 파일을 두 워크트리가 고치면 충돌 확정. 소유권 단일화(kb-182)가 낫다. 기각.

## R6. 경로·에러코드

- **Decision**: 엔드포인트 `POST /api/v1/admin/foods` (`ApiPaths.ADMIN = "$V1/admin"`). 에러코드 `ADMIN_FORBIDDEN("AUTH-008", 403, ...)` 신설 — 첫 403 이며 기존 AUTH 시퀀스(001~007) 연장.
- **Rationale**: admin 리소스는 프리픽스로 격리해야 인터셉터·필터 패턴 한 줄로 이후 admin API 가 전부 커버된다. RESTful 하게 "foods 컬렉션에 POST".
- **Alternatives considered**: `/api/v1/foods/seed` — admin 격리 프리픽스가 없어 가드를 엔드포인트마다 반복하게 됨. 기각.
