# kbap 관리자 API 문서 (Thymeleaf → React 분리용)

기준 커밋: develop `cc8a7bf2` (2026-08-25) · 소스: `api/src/main/kotlin/com/kbap/api/admin/`

관리자 기능은 현재 **두 층**으로 나뉘어 있다.

| 층 | 경로 | 인증 | 소비자 |
|---|---|---|---|
| **A. 화면(Thymeleaf)** | `/admin/**` | 쿠키 `ADMIN_TOKEN` (로그인 폼) | 사람(브라우저) |
| **B. REST API** | `/api/admin/**` | `Authorization: Bearer <JWT(role=ADMIN)>` + `X-API-Version` | 기계 클라이언트(kbap-langchain 등) |

React 로 분리한다는 것은 **A 층을 없애고, A 가 화면에 뿌리던 데이터·액션을 B 층 REST 로 옮기는 일**이다. 이 문서는 (1) A 층 화면별 데이터/액션 인벤토리, (2) 지금 있는 B 층 REST, (3) React 전환 시 새로 만들어야 할 REST 제안, (4) 노출 정리 후보를 정리한다.

---

## 1. 인증 모델

### 1-1. 화면 층 (현재)

- `POST /admin/login` (form: `id`, `password`) → `AdminLoginService.login` — `admin_account` 테이블의 `login_id` + BCrypt 비밀번호 검증 → **일반 회원과 같은 JWT access 토큰**(`role=ADMIN`)을 발급해 쿠키로 심는다.
  - 쿠키: `ADMIN_TOKEN`, `HttpOnly` · `Secure` · `SameSite=Strict` · `Path=/admin`
- `POST /admin/logout` → 쿠키 만료
- `AdminPageAuthInterceptor` (`/admin/**`, `/admin/login` 제외): 쿠키 없음/파싱 실패/role≠ADMIN → `302 /admin/login`. **POST 요청은 `Origin` 헤더가 자기 오리진과 다르면 거부**(CSRF 방어).

### 1-2. REST 층 (현재)

- `JwtAuthenticationFilter` 가 `/api/admin/*` 를 보호 → 토큰 없음/만료 시 401(`AUTH-00x`)
- `AdminAuthorizationInterceptor` (`/api/admin/**`): 토큰의 role 이 `ADMIN` 이 아니면 **`AUTH-008` (403) ADMIN_FORBIDDEN**
- 모든 `/api/**` 는 **`X-API-Version` 헤더 필수** (현재 관리자 REST 는 전부 `1.0+`)
- 응답 봉투: `{ success, payload, message, code }` (`BaseResponse`)
- CORS: `/api/**` 에 `allowedOriginPatterns("*")` + `allowCredentials(true)` — 별도 오리진의 React 앱에서 호출 가능

### 1-3. React 전환 시 필요한 것

- **JSON 로그인 엔드포인트가 없다.** 현재 로그인은 form POST + 쿠키(`Path=/admin`)뿐이라 별도 오리진 SPA 는 쓸 수 없다. 제안: `POST /api/admin/auth/login {id,password}` → `{ accessToken }` (기존 `AdminLoginService.login` 재사용, 만료 시 재로그인 — 관리자는 refresh 불필요).
- 토큰은 access 토큰이라 **일반 회원 API 에도 통한다**(role 만 다름). 관리자 전용 토큰으로 분리할지는 별도 판단.

---

## 2. 화면(Thymeleaf) 인벤토리 — React 화면으로 옮길 대상

템플릿: `api/src/main/resources/templates/admin/{layout,login,foods,food-list,food-seed,food-images,members,member-detail}.html`

### 2-1. 대시보드 `GET /admin/foods` → `foods.html`

한 화면에 4개 데이터 묶음을 뿌린다.

| 모델 | 소스 | 내용 |
|---|---|---|
| `dashboard` | `AdminFoodDashboardService.getDashboard()` | 음식 콘텐츠 상태 집계 |
| `outbox` | `AdminFoodOutboxQueryService.getOutboxDashboard()` | 콘텐츠 아웃박스(랭체인 수집) 상태 + 최근 20건 |
| `vectorOutbox` | `AdminFoodDashboardService.getVectorOutboxDashboard()` | 벡터 동기화 아웃박스 상태 + 실패 20건 |
| `metrics` | `AdminDashboardMetricsService.getMetrics()` | 활성 회원·주간 스캔·주간 신규 음식·LLM 비용(7일) |

**데이터 모델**

```
AdminFoodDashboardView
  total, failed, pendingImage, pendingReview, ready: Long
  readyRatio: Double            // 소수 1자리 %

AdminFoodOutboxDashboardView
  pending, sent, complete: Long
  recent: [{ foodId, displayName, outboxStatus(PENDING|SENT|COMPLETE), attempts, createdAt, sentAt? }]  // 최근 20

AdminVectorOutboxDashboardView
  pending, complete, failed, unenqueued: Long
  failures: [{ outboxId, foodId, displayName, operation(UPSERT|DELETE), attempts, lastError?, updatedAt }] // 실패 최근 20

AdminDashboardMetricsView
  totalActiveMembers: Long
  weeklyScans:    [{ date, dayLabel(월~일), count, heightPct }]   // 7일, heightPct 는 막대 높이용(0~100)
  weeklyNewFoods: [{ date, dayLabel, count, heightPct }]
  llmCostDaily:   [{ date, dayLabel, callCount, costUsd, models: [{ modelName, callCount, inputTokens, outputTokens, costUsd }] }]
```

> `heightPct`·`dayLabel` 은 서버가 차트 렌더링용으로 미리 계산해 준 값 — React 로 가면 클라이언트 계산이 자연스러우니 REST 응답에선 빼도 된다.

**액션**

| 액션 | 현재 | 동작 |
|---|---|---|
| 벡터 동기화 일괄 enqueue | `POST /admin/foods/vector-outboxes/enqueue` | READY 인데 UPSERT 아웃박스가 없는 음식을 최대 500건 enqueue |
| 벡터 아웃박스 재시도 | `POST /admin/foods/vector-outboxes/{outboxId}/retry` | FAILED 건만 `retry()` (PENDING 으로 되돌림). 없는 id 는 무시 |

### 2-2. 음식 목록/상세/수정 `GET /admin/foods/list` → `food-list.html`

**쿼리 파라미터**: `page`(1-base, 기본 1), `q`(displayName contains), `status`(FoodContentStatus), `detail`(음식 id — 우측 오버레이 패널), `edit`(true 면 편집 모드)

**목록 모델**

```
AdminFoodListPageView
  items: [AdminFoodSummaryView]
  page, totalPages: Int · totalCount: Long · hasPrev, hasNext: Boolean
  query?: String · status?: FoodContentStatus
  // 페이지 크기 200 고정(LIST_PAGE_SIZE), 정렬 id DESC

AdminFoodSummaryView
  id, koreanName(=displayName), contentStatus, contentFailureKind?, spiciness, imageUrl?, updatedAt
```

**상세 모델** (`detail` 지정 시 `foodDetail`)

```
AdminFoodDetailView
  id, koreanName, description, spiciness, contentStatus, contentFailureKind?, contentReviewRejectionReason?
  imageRef?, imageUrl?
  nameTranslationsJson, descriptionTranslationsJson, ingredientsJson: String   // pretty JSON 문자열(textarea 편집용)
  version: Long, createdAt, updatedAt
```

> `*Json` 문자열은 Thymeleaf textarea 를 위한 형태다. REST 로 옮기면 `nameTranslations: Map<String,String>`, `descriptionTranslations: Map<String,String>`, `ingredients: [{ code, inclusionPercent }]` 구조로 내보내는 게 맞다.

**액션**

| 액션 | 현재 | 파라미터 | 결과 |
|---|---|---|---|
| 음식 수정 | `POST /admin/foods/{id}` | `koreanName*`, `description*`, `spiciness*`(0~10), `contentStatus*`, `imageRef`, `nameTranslationsJson`, `descriptionTranslationsJson`, `ingredientsJson` (+목록 복귀용 `page/q/status`) | `UPDATED` / `NOT_FOUND` / `INVALID_NAME`(한글 정규화 후 빈 문자열) / `INVALID_JSON` / `DUPLICATE_NAME`(정규화 키 중복). READY 로 바뀌면 벡터 UPSERT, READY 에서 벗어나면 DELETE 아웃박스 enqueue |
| 음식 삭제(소프트) | `POST /admin/foods/{id}/delete` | — | `DELETED` / `NOT_FOUND`. 벡터 DELETE enqueue |
| 재수집 요청 | `POST /admin/foods/recollect` | `q`, `status` (현재 필터 그대로) | 필터에 걸린 음식 전부를 콘텐츠 아웃박스(PENDING)로 등록. **최대 500건**(초과 시 `too-many`), 이미 PENDING 인 건 skip. 결과 `{ requested, created, skipped, exceeded, max }` |

현재 결과 전달은 redirect 쿼리스트링(`?updated=<id>`, `?error=not-found|invalid-name|invalid-json|duplicate-name`, `?recollected=&recollectSkipped=`, `?recollectError=too-many|no-target|failed&recollectMax=`) — React 에선 REST 응답 코드/페이로드로 대체.

### 2-3. 시드 등록 `GET/POST /admin/foods/seed` → `food-seed.html`

- 입력: `koreanNames` textarea (줄 단위). 검증: 비어있음 / **500개 초과** / 이름 **255자 초과**
- 동작: 한글 정규화 키 기준 중복 제거 → 이미 있는 음식 skip → 없는 것만 **INCOMPLETE(=PENDING_REVIEW 파이프라인 시작점) 상태로 생성**
- 결과: `{ requested, created, skipped }` — redirect `?seeded=&skipped=` / `?error=empty-seed|too-many-names|name-too-long|no-valid-names|seed-failed`
- **동일 기능의 REST 가 이미 있다**: `POST /api/admin/foods` (§3-1) — React 는 이걸 그대로 쓰면 된다.

### 2-4. 이미지 배치 `GET/POST /admin/foods/images` → `food-images.html`

- `GET`: 최근 이미지 배치 20건
  ```
  AdminImageBatchView
    id, batchStatus(ImageBatchStatus), model, promptVersion, submittedAt, collectedAt?
    pendingCount, doneCount, failedCount, totalCount
  ```
- `POST`: 이미지 없는 음식들을 OpenAI 이미지 배치에 제출 → `{ submittedFoodCount, submittedBatchCount }`
- **동일 기능의 REST 가 이미 있다**: `POST /api/admin/foods/images` (§3-1). 목록 조회 REST 는 없음(신규 필요).

### 2-5. 회원 목록/상세 `GET /admin/members`, `GET /admin/members/{id}` → `members.html`, `member-detail.html`

```
AdminMemberPageView   // page 크기 20, id DESC
  items: [AdminMemberSummaryView], page, totalPages, totalCount, hasPrev, hasNext

AdminMemberSummaryView
  id, nickname?, email?, provider(GOOGLE|APPLE), memberStatus, onboardingCompleted, createdAt

AdminMemberDetailView
  id, nickname?, email?, provider, providerUid, memberStatus, onboardingCompleted
  profileImageUrl?, avoidanceSubstanceCodes: [String], spicinessPreference, countryCode?
  scanCount, reviewCount, rankingTier, createdAt
```

- 조회 전용(수정/삭제 액션 없음). 검색·필터 없음(페이지네이션만).
- 상세는 없는 id 면 `member` 모델 없이 빈 화면을 렌더링한다(404 아님).

### 2-6. 기타

- `GET /admin` → `/admin/foods` 로 redirect (홈 = 대시보드)
- `GET /admin/login`: 이미 인증돼 있으면 `/admin` 으로 redirect

---

## 3. 현재 REST API (`/api/admin/**`, 모두 `X-API-Version: 1.0` 이상)

### 3-1. 음식 (`AdminController`)

| 메서드 | 경로 | 요청 | 응답 payload |
|---|---|---|---|
| POST | `/api/admin/foods` | `{ koreanNames: [String] }` (최대 500) | `{ requested, created, skipped }` — §2-3 시드와 동일 로직 |
| POST | `/api/admin/foods/images` | (body 없음) | `{ submittedBatchCount, submittedFoodCount }` — §2-4 와 동일 |

### 3-2. 앱 버전 (`AdminAppVersionController`)

| 메서드 | 경로 | 요청 | 응답 payload |
|---|---|---|---|
| GET | `/api/admin/app-version` | — | `{ minSupportedVersion, latestVersion, storeUrls: { ios?, aos? } }` |
| PUT | `/api/admin/app-version` | `{ minSupportedVersion*, latestVersion* (semver `x.y.z`), iosStoreUrl?, aosStoreUrl? (≤512자) }` — **전체 치환** | 위와 동일 |

> 화면(Thymeleaf)엔 앱 버전 관리 페이지가 **없다** — REST 만 있음. React 에서 화면으로 노출할 후보.

### 3-3. 콘텐츠 검수 (`AdminFoodContentReviewController`) — 기계 클라이언트용

| 메서드 | 경로 | 요청 | 응답 payload |
|---|---|---|---|
| GET | `/api/admin/foods/content-reviews?limit=N` | `limit` 기본값 서비스 상수 | `{ items: [{ foodId, koreanName, description, nameTranslations, descriptionTranslations, ingredients, spiciness, imageUrl?, contentReviewAttempts }] }` — PENDING_REVIEW 대상 |
| POST | `/api/admin/foods/content-reviews/{foodId}` | `{ passed*: Boolean, reason?: String }` | `{ foodId, contentStatus, contentReviewAttempts, contentReviewRejectionReason? }` |

### 3-4. 콘텐츠 적재 (`AdminFoodContentIngestController`) — kbap-langchain 전용

| 메서드 | 경로 | 요청 | 응답 |
|---|---|---|---|
| POST | `/api/admin/foods/contents` | `{ outboxId*, foodId*, passed*, displayName?, description, longDescription?(≤1000), spiciness(0~10), nameTranslations, descriptionTranslations, ingredients: [{code, inclusionPercent}], failureKind?, reason? }` | `payload: null` (Unit) |

- `passed=true` 면 `description(1~255)`·`spiciness`·`nameTranslations`/`descriptionTranslations`(**9개 대상 언어 전부** 비어있지 않아야)·`ingredients`(필수, 없으면 `[]`; 코드는 `IngredientCode` 카탈로그에 있어야, percent 0~100) 검증
- `passed=false` 면 `failureKind*`(NOT_FOOD | JUDGE_REJECTED | INGREDIENT_GUARD)·`reason*` 필수

> 3-3·3-4 는 **사람 UI 용이 아니다**(랭체인 파이프라인 콜백). React 관리자에서 호출할 일 없음 — 문서화·권한은 유지하되 화면 메뉴에 넣지 않는다.

---

## 4. React 전환 시 신설이 필요한 REST (제안)

화면 컨트롤러가 직접 서비스를 호출하던 것들을 JSON 으로 노출해야 한다. 기존 `Admin*Service`·View DTO 를 그대로 재사용하면 컨트롤러만 추가하면 된다.

| 대체 대상 | 제안 엔드포인트 | 비고 |
|---|---|---|
| 로그인 | `POST /api/admin/auth/login` → `{ accessToken }` | §1-3. `X-API-Version` 필수 규약 그대로 |
| 대시보드 | `GET /api/admin/dashboard` → `{ foods, outbox, vectorOutbox, metrics }` | 4개 서비스 응답 한 봉투. `heightPct/dayLabel` 제거 가능 |
| 벡터 enqueue | `POST /api/admin/foods/vector-outboxes/enqueue` → `{ enqueued }` | 현재는 건수도 안 돌려줌 — 반환값 추가 권장 |
| 벡터 재시도 | `POST /api/admin/foods/vector-outboxes/{id}/retry` | FAILED 아니면 무시 → 404/409 로 명확화 권장 |
| 음식 목록 | `GET /api/admin/foods?page&q&status&size` | 200 고정 → `size` 파라미터화 권장 |
| 음식 상세 | `GET /api/admin/foods/{id}` | JSON 문자열 3종 → 구조화 필드로 |
| 음식 수정 | `PUT /api/admin/foods/{id}` | 결과 enum → HTTP 코드: NOT_FOUND 404 · INVALID_* 400 · DUPLICATE_NAME 409 |
| 음식 삭제 | `DELETE /api/admin/foods/{id}` | 소프트 삭제 |
| 재수집 | `POST /api/admin/foods/recollect?q&status` → `AdminFoodRecollectResult` | `exceeded` 는 400/409 로 |
| 이미지 배치 목록 | `GET /api/admin/foods/images` → `[AdminImageBatchView]` | 제출(POST)은 이미 있음 |
| 회원 목록 | `GET /api/admin/members?page` | 검색/필터 추가 여지 |
| 회원 상세 | `GET /api/admin/members/{id}` | 없으면 404 |

경로 규약(CLAUDE.md): `ApiPaths.ADMIN` 상수 사용, 신규 경로는 `WebConfig` 의 JWT 보호 목록(`/api/admin/*` 가 이미 prefix 로 덮음) 확인, `X-API-Version` 헤더 매핑(`version = "1.0+"`), 응답은 `ResponseEntity<BaseResponse<T>>`.

---

## 5. 노출 정리 후보 ("불필요하게 노출되는 것")

| 항목 | 현재 | 의견 |
|---|---|---|
| 회원 상세 `providerUid` | 노출 | 소셜 provider 의 내부 식별자 — 운영에 필요 없음. 제거 권장 |
| 회원 상세 `email` | 노출 | 개인정보. 필요하면 마스킹(`ab***@gmail.com`) 또는 상세에서만 |
| 음식 상세 `version` | 노출 | 낙관적 락 값 — 편집 폼에 숨김 필드로만 필요(수정 요청에 동봉해 lost update 방지). 화면 표시 불필요 |
| 대시보드 `heightPct`, `dayLabel` | 노출 | 서버 렌더링 잔재. React 에선 클라이언트 계산 |
| 음식 상세 `imageRef` | 노출 | S3 키 원문. `imageUrl` 만으로 충분하나 편집(교체)엔 필요 — 편집 모드에서만 |
| 벡터 아웃박스 `lastError` 원문 | 노출 | 예외 메시지 전문이 그대로 나올 수 있음. 길이 제한/요약 권장 |
| 콘텐츠 아웃박스 최근 20 · 벡터 실패 20 | 고정 | React 에선 페이지네이션/필터로 |
| 쿠키 `Path=/admin` | 화면 전용 | React 전환 후 화면 층(`/admin/**`, 템플릿, `AdminPageAuthInterceptor`, `AdminPageController`, `Admin*PageController`) 전부 삭제 대상 |
| 3-3/3-4 기계용 API | 같은 `/api/admin` 접두 | UI 와 섞이지 않게 문서(Swagger 그룹)에서 구분. 장기적으로 별도 클라이언트 자격(서비스 토큰)으로 분리 검토 |

---

## 6. 참고 — enum

| enum | 값 |
|---|---|
| `FoodContentStatus` | `FAILED`, `PENDING_IMAGE`, `PENDING_REVIEW`, `READY` |
| `FoodContentFailureKind` | `NOT_FOOD`, `JUDGE_REJECTED`, `INGREDIENT_GUARD` |
| `FoodContentOutboxStatus` | `PENDING`, `SENT`, `COMPLETE` |
| `FoodVectorOutboxStatus` / `Operation` | `PENDING`, `COMPLETE`, `FAILED` / `UPSERT`, `DELETE` |
| `SocialProvider` | `GOOGLE`, `APPLE` |
| `MemberRole` | (일반) / `ADMIN` |

관련 에러 코드: `AUTH-008` ADMIN_FORBIDDEN(403), `COMMON-002` X-API-Version 누락/미지원(400).
