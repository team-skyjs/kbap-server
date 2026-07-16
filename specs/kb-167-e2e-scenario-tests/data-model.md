# Data Model: KB-167 E2E 시나리오 테스트

DB 스키마·엔티티 변경 없음. 이 문서는 테스트 코드의 구조 계약 — `ScenarioApiDriver` 의 스텝 메서드와 여정 상태 — 를 정의한다.

## ScenarioApiDriver (여정당 1 인스턴스)

생성: `ScenarioApiDriver(mockMvc, 여정접두어)` — 생성 시 `"scenario-<접두어>-<UUID>"` idToken 을 만들어 여정 전용 신규 소셜 계정을 확정한다. `jacksonObjectMapper()` 내부 생성(기존 관행).

### 여정 상태 (드라이버 필드 — 스텝 간 전달, FR-002)

| 필드 | 채워지는 스텝 | 쓰는 스텝 |
|------|------|------|
| `idToken` | 생성자 | 회원가입한다/재로그인한다 |
| `accessToken` / `refreshToken` | 회원가입한다·토큰을_갱신한다·재로그인한다 | 인증 필요한 모든 스텝 |
| `objectKey`(이미지 경로) | 업로드URL을_발급받는다 | 업로드를_완료한다·스캔한다 |
| `foodId` | 음식을_검색한다(응답에서 발견) | 음식_상세를_조회한다·북마크한다 |

### 스텝 메서드 계약 (한국어 메서드명 — 시나리오 본문이 조립)

| 메서드 | HTTP | 반환(단언 재료) |
|------|------|------|
| `회원가입한다()` | `POST /api/v1/auth/login` | `newMember: Boolean` — 토큰 필드 갱신 |
| `재로그인한다()` | 같은 idToken 으로 login | `newMember: Boolean` |
| `온보딩한다(nickname, codes, spiciness…)` | `POST /api/v1/members/me/onboarding` | 200 확인 |
| `홈을_조회한다()` | `GET /api/v1/home` | payload JsonNode(`authenticated`·`avoidedSubstances`·`recentScans`) |
| `음식을_검색한다(keyword)` | `GET /api/v1/foods/search` | 검색 결과 items — 첫 매칭의 `foodId` 를 상태에 저장 |
| `음식_상세를_조회한다()` | `GET /api/v1/foods/{foodId}` | payload(`overallRiskStatus`·`bookmarked`) |
| `북마크한다()` | `POST /api/v1/bookmarks` | 200 확인 |
| `북마크_목록을_조회한다()` | `GET /api/v1/bookmarks` | items(foodId 목록) |
| `만료된_액세스토큰으로_프로필을_조회한다()` | `GET /api/v1/members/me/profile` + 음수 TTL 토큰 | HTTP status + `code`(AUTH-004 기대) |
| `토큰을_갱신한다()` | `POST /api/v1/auth/refresh` | 새 토큰으로 상태 갱신, 구 토큰 반환(무효 확인용) |
| `구_리프레시토큰으로_갱신을_시도한다(old)` | `POST /api/v1/auth/refresh` | HTTP status + `code`(거절 기대) |
| `로그아웃한다()` | `POST /api/v1/auth/logout` | 200 확인 |
| `프로필을_조회한다()` | `GET /api/v1/members/me/profile` | payload 또는 실패 응답(탈퇴 후 무효 확인 겸용) |
| `업로드URL을_발급받는다(contentType, size)` | `POST /api/v1/images/upload-url` | `objectKey` 저장, `uploadUrl` 등 |
| `업로드를_완료한다()` | `POST /api/v1/images/complete` | 200 + path — 선행: `FakeStorageObjectStore.put(objectKey, …)` |
| `스캔한다(items)` | `POST /api/v1/scans` | `results[{matched, foodId, riskLevel}]` — 선행: vision 페이크 `program(objectKey, menus)` |
| `탈퇴한다()` | `PATCH /api/v1/auth/withdraw` | 200 확인 |

> 정확한 시그니처(파라미터·반환 타입)는 test-writer 재량 — 계약의 본질은 (1) 한국어 메서드명, (2) MockMvc·JSON 파싱 비노출, (3) 여정 상태의 필드 보유.

## ScenarioSocialTokenVerifierConfig

- `@TestConfiguration` + `@Primary` `SocialTokenVerifier`: `verify(idToken)` → `SocialIdentity(GOOGLE, sub = idToken, email = null 또는 파생)`.
- 효과: idToken 이 곧 계정 식별자 — 여정마다 UUID 포함 토큰으로 신규 가입 보장(FR-005). `SocialAccountDeleter` 도 no-op 페이크 필요(탈퇴 여정 — 기존 `FakeSocialAccountDeleter` 패턴).

## ScenarioFoodSeed

- `ensureFood(dataSource, koreanName, spiciness, substances: Map<code, percent>)`:
  - `food` INSERT — **id 명시 없이**(auto-increment, 타 테스트 고정 id 1~4 와 충돌 회피), korean_name 은 여정 고유(예: "시나리오된장찌개").
  - 참조 기피물질: `avoidance_substance` 에 code 존재 확인 후 **부재 시에만 INSERT**(auto-increment id). DELETE 문 금지 — Flyway 카탈로그 81종 보존.
  - `food_avoidance_substance` 매핑 INSERT(생성된 food id 사용 — `RETURN_GENERATED_KEYS` 또는 korean_name 재조회).
- foodId 는 시드 반환값이 아니라 **검색 API 응답에서 발견**해도 된다(해피패스는 검색 스텝이 어차피 있다).

## 시나리오 스펙 4개 (여정당 1 파일, `@Tags("scenario")`)

| 스펙 | 여정 서사(then 본문의 조립 순서) |
|------|------|
| `HappyPathScenarioTest` | 회원가입한다 → 온보딩한다 → 홈을_조회한다 → 음식을_검색한다 → 음식_상세를_조회한다 → 북마크한다 → 북마크_목록을_조회한다 |
| `AuthLifecycleScenarioTest` | 회원가입한다 → 만료된_액세스토큰으로_프로필을_조회한다(AUTH-004) → 토큰을_갱신한다 → 프로필을_조회한다(성공) → 로그아웃한다 → 구_리프레시토큰으로_갱신을_시도한다(거절) → 재로그인한다 |
| `MenuScanScenarioTest` | 회원가입한다 → 온보딩한다 → 업로드URL을_발급받는다 → 업로드를_완료한다 → 스캔한다 → 홈을_조회한다(recentScans 노출) |
| `WithdrawScenarioTest` | 회원가입한다 → 온보딩한다 → (음식 시드+검색) → 북마크한다 → 탈퇴한다 → 프로필을_조회한다(실패) → 구_리프레시토큰으로_갱신을_시도한다(거절) → 재로그인한다(newMember=true) → 북마크_목록을_조회한다(0건) |
