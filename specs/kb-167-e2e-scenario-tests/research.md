# Research: KB-167 E2E 시나리오 테스트

조사 대상: `app/api/src/test` 기존 인프라·페이크·API 표면. NEEDS CLARIFICATION 0건으로 시작했고, 조사에서 발견한 결정 5건을 기록한다.

## R1. 시나리오 본문 구성 — 한국어 스텝 메서드 조립

- **Decision**: `ScenarioApiDriver` 클래스가 한국어 이름의 스텝 메서드(`회원가입한다()`·`온보딩한다()`·`음식을_검색한다()`·`스캔한다()` 등)를 제공하고, 시나리오 `then` 블록은 이 메서드들을 순서대로 조립한다. 드라이버 인스턴스가 여정 상태(토큰·objectKey·foodId)를 필드로 보유한다.
- **Rationale**: 사용자 지시("회원가입 후 스캔하다 시나리오는 회원가입()·스캔() 메서드를 조립"). Kotlin 식별자는 한국어를 백틱 없이 허용하므로 컴파일 문제 없음. 기존 컨벤션(given/when/then 한국어)과도 결이 같다. MockMvc 원시 호출이 본문에 노출되지 않아 FR-004 충족.
- **Alternatives considered**: (a) 시나리오마다 로컬 함수(기존 `MemberFoodJourneyTest` 방식) — 4개 스펙에 중복되고 서사가 약함. (b) Kotest DSL 확장 함수 — 과설계, 헬퍼 클래스 하나로 충분.

## R2. 여정별 계정 격리 — idToken→sub 파생 페이크

- **Decision**: `scenario/ScenarioSocialTokenVerifierConfig.kt` — `@TestConfiguration` + `@Primary` `SocialTokenVerifier` 로 **idToken 문자열을 소셜 sub 로 그대로(또는 접두 파생) 사용**. 각 여정은 `"scenario-<여정명>-<UUID>"` 형 idToken 하나를 만들어 로그인 — 항상 신규 계정.
- **Rationale**: 기존 `FakeSocialTokenVerifier`(`AuthControllerTest.kt` 하단)는 고정 `google-sub-fixed` 를 반환 — 여정 4개가 같은 회원을 공유하게 되고, 탈퇴 여정과 해피패스가 간섭한다. 기존 테스트들은 `DELETE FROM member` 로 격리하지만 spec(FR-005)은 테이블 청소 없는 회원 단위 격리를 요구한다. `@Import` 는 스펙 단위 적용이라 기존 테스트 무영향.
- **Alternatives considered**: (a) 기존 페이크 재사용 + 테이블 청소 — FR-005 위반, 병행 테스트 간섭. (b) 기존 `FakeSocialTokenVerifier` 를 idToken 파생으로 개조 — `AuthControllerTest` 등 기존 스펙의 전제(같은 토큰=같은 회원, 고정 sub)와 얽혀 회귀 위험. 시나리오 전용 신규가 더 작다.

## R3. 만료 액세스 토큰 재현

- **Decision**: `JwtTokenIssuer(authTokenProperties.copy(accessTtl = Duration.ofMinutes(-1))).issueAccessToken(...)` — 같은 secret 으로 서명된 이미 만료된 토큰을 즉석 생성.
- **Rationale**: `JwtAuthenticationFilterTest`(106~112행)의 기존 선례 그대로. 테스트 yml TTL 은 access 30m 이라 자연 만료 불가, sleep 은 금물. `AuthTokenProperties` 는 data class 빈이라 `copy` 가능, 시나리오에서 `@Autowired` 로 받는다.
- **Alternatives considered**: (a) 테스트 yml TTL 단축 — 다른 테스트 전부에 영향. (b) Clock 주입 리팩터링 — 프로덕션 코드 변경 금지 제약 위반.

## R4. "스캔 히스토리 조회" 스텝 매핑

- **Decision**: `GET /api/v1/home` 응답의 `recentScans` 로 검증한다.
- **Rationale**: 전용 스캔 히스토리 GET 엔드포인트가 존재하지 않는다(`ScanController` 는 `POST /scans` 뿐, 필터 URL 패턴에 `/scans/*` 가 예약만 됨). 스캔 이력의 유일한 조회 창구가 홈 `recentScans` 다. 신규 엔드포인트 추가는 이번 범위(프로덕션 0줄) 밖.
- **Alternatives considered**: `GET /api/v1/scans` 신설 — 별도 이슈로 분리해야 할 프로덕션 변경.

## R5. 음식 데이터 준비 — insert-if-absent, 카탈로그 보존

- **Decision**: `scenario/ScenarioFoodSeed.kt` — raw JDBC 로 (a) food 를 **id 명시 없이(auto-increment)** INSERT 하고 여정 고유 korean_name 으로 검색 API 에서 foodId 를 발견, (b) 참조 기피물질은 code 기준 **존재 시 skip, 부재 시 INSERT**(삭제 없음).
- **Rationale**: KB-163 이후 마이그레이션에 음식 0건 — 검색·상세·북마크·스캔 매칭 스텝은 READY 음식이 필요하다. 기존 `FoodTestSeed.clear()` 는 `DELETE FROM avoidance_substance` 로 Flyway 카탈로그 81종을 파괴한다 — spec 의 "마스터 읽기 전용" 전제와 충돌하므로 시나리오에서 금지. 고정 id INSERT 는 다른 테스트 시드(id 1~4, 101~103)와 충돌 위험이 있어 auto-increment + API 로 id 발견이 안전하다. insert-if-absent 는 다른 테스트 클래스가 카탈로그를 지운 뒤 실행되는 순서에도 견딘다.
- **Alternatives considered**: (a) `FoodTestSeed` 재사용 — clear 가 카탈로그 파괴 + 고정 id 충돌. (b) 도메인 서비스로 생성 — `FoodService` 에 READY 음식 공개 create 가 없고(`createIncomplete` 뿐), 테스트용 API 를 뚫는 것은 원칙 IV 취지 훼손.

## 재사용하는 기존 인프라 (신규 아님)

| 구성 | 위치 | 재사용 방식 |
|------|------|------|
| `MySqlContainerConfig` / `RedisContainerConfig` | `core/src/testFixtures/.../testsupport/` | `@Import` |
| `FakeMenuBoardVisionExtractor`(`FakeVisionConfig`) | `app/api/src/test/.../scan/` | 항상 스캔되는 `@Configuration` — `program(path, menus)` 로 스캔 결과 주입 |
| `FakePresignedUploadPortConfig` | `app/api/src/test/.../upload/` | 항상 스캔 — upload-url 발급 페이크 |
| `FakeStorageObjectStore`(`FakeStorageConfig`) | `app/api/src/test/.../image/` | 항상 스캔 — `put(path, contentType, size)` 로 head 응답 주입 후 `/images/complete` |
| `@Tags` + `-Dkotest.tags` 전달 | `ModuleBoundaryTest`(`arch` 태그) + `kbap.kotlin-common.gradle.kts` | 동일 메커니즘으로 `scenario` 태그 |
| 만료 토큰 생성 | `JwtAuthenticationFilterTest` | `AuthTokenProperties.copy(accessTtl = 음수)` 선례 |

## 확인된 계약 포인트 (시나리오가 단언할 값)

- 로그인: `payload.newMember`(boolean)·`accessToken`·`refreshToken`. 탈퇴 후 재로그인 시 `newMember=true`.
- 만료 access 로 보호 API 호출: HTTP 401 + `code="AUTH-004"`(`EXPIRED_ACCESS_TOKEN`) — `JwtAuthenticationFilter` 가 `BaseResponse.fail` JSON 직접 기록.
- refresh 는 rotation — 갱신 후 구 refreshToken 은 무효. 로그아웃 후 refresh 거절(Redis 스토어 삭제).
- 탈퇴 후: 회원 소프트삭제 — 구 accessToken 의 회원 API 호출은 실패(200 아님), 구 refreshToken 갱신 거절.
- 홈: `authenticated`·`avoidedSubstances[{code,name}]`·`recentScans[FoodSummaryResponse]`.
- 스캔: `results[{matched, foodId, riskLevel, ...}]` — vision 페이크 `ExtractedMenu.matchedIdx`·korean_name 일치로 matched=true 유도.
