# Research: 홈 화면 조회 (KB-111)

기존 코드 조사로 확인한 사실과 그 위에서 내린 설계 결정. 미해결 NEEDS CLARIFICATION 없음.

## R1 — 선택적 인증(비회원 허용 + 무효 토큰 401)

**현황**: `JwtAuthenticationFilter`(OncePerRequestFilter)는 `WebMvcAuthConfig` 의 `FilterRegistrationBean` 으로 **`/api/v1/members/*` 에만** 등록돼 있고 헤더 없음·위조·만료 모두 401 로 막는다(강제 인증). `@AuthMemberId` 리졸버는 필터가 세팅한 `authMemberId` request attribute 를 읽을 뿐이다. `TokenParser.parseAccessToken(token): ParsedAccessToken(memberId, role)` 은 위조=`INVALID_ACCESS_TOKEN`, 만료=`EXPIRED_ACCESS_TOKEN` 로 `AuthException`(=`MeogoException`) 을 던지고, `GlobalExceptionHandler` 가 401 `BaseResponse.fail` 로 변환한다.

**Decision**: `/api/v1/home` 은 강제 필터 밖(`/members/*` 아님)이라 그대로 통과한다. 여기에 **선택 인증 리졸버** `@AuthMemberIdOrNull` + `AuthMemberIdOrNullArgumentResolver` 를 신설한다:
- `Authorization` 헤더 없음/`Bearer ` 아님 → `null` 반환(비회원).
- 헤더 있음 → `tokenParser.parseAccessToken(token).memberId` 반환. 위조·만료면 `AuthException` 을 그대로 던져 `GlobalExceptionHandler` 가 401 처리.

`WebMvcAuthConfig.addArgumentResolvers` 에 등록(이미 `TokenParser` 주입 중). Spring Security 미도입 기조 유지.

**Rationale**: 강제 필터를 건드리지 않아 기존 `/members/*` 인증이 안전. "헤더 없음=비회원 / 헤더 있는데 무효=401" 구분이 리졸버 한 곳에 응집. spec Edge Case("위조·만료 토큰은 비회원 폴백 없이 401")를 정확히 만족.

**Alternatives**: (a) 필터를 `/home` 에도 걸고 익명 통과 분기 추가 → 강제/선택 두 모드가 한 필터에 섞여 복잡. (b) 컨트롤러에서 직접 헤더 파싱 → 매 컨트롤러 중복. 리졸버가 재사용성·응집 우위.

## R2 — 기피 성분 프로바이더 교체(고정 5개 → 회원 프로필)

**현황**: `AvoidedSubstanceProvider.avoidedCodes(): Set<AvoidanceSubstanceCode>`(무인자). `MockAvoidedSubstanceProvider @Component` 가 SOY·MILK·PEANUT·SHRIMP·EGG 고정 반환. 소비자 4곳: `BrowseFoodsUseCase`·`SearchFoodsUseCase`·`GetFoodDetailUseCase`·`ScanUseCase`. 회원 프로필은 `Member.profile.avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>`(문자열 `.value`), `MemberRepository.findById(id): Member?`.

**Decision**: 인터페이스를 `avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>` 로 바꾸고 `MemberAvoidedSubstanceProvider`(신규 `@Component`)로 교체, `MockAvoidedSubstanceProvider` 삭제:
- `memberId == null`(비회원) → `emptySet()`.
- 회원 → `memberRepository.findById(memberId)?.profile?.avoidanceSubstanceCodes` 를 `AvoidanceSubstanceCode` enum 으로 매핑(값 문자열 → `valueOf`). 회원 미존재·미설정 → `emptySet()`.

소비자 4개 유스케이스 Input 에 `memberId: Long?`(기본 null) 추가, 각 컨트롤러에서 `@AuthMemberIdOrNull` 로 주입. 즉 음식 목록/검색/상세/스캔이 **선택 인증**으로 개인화된다(비회원은 위험도 미강조 = UNKNOWN, 회원은 본인 기피 기준).

**Rationale**: spec US4·FR-005 의 요구(고정값 → 프로필 기반, 기존 기능 무회귀)를 충족. member 는 port(`MemberRepository`)로만 접근 → 헌법 III. 프로바이더가 member·food 를 잇는 조합은 `:application:client` 안이라 헌법 II 준수.

**회귀 주의**: 기존 food/scan **통합 테스트**는 실 빈(Mock 고정 5개)에 기대어 위험도를 단언한다. Mock 삭제 후에는 (1) 비회원 호출 시 avoided=empty 라 위험도 기대값을 조정하거나, (2) 회원+토큰+프로필 시드로 특정 기피를 넣어 단언하도록 갱신해야 한다. 유스케이스 단위 테스트의 페이크 프로바이더는 시그니처(`memberId`)만 맞추면 된다. 이 갱신을 task 로 명시한다(헌법 I).

**Alternatives**: 무인자 유지 + ThreadLocal/요청스코프로 memberId 주입 → 암묵 전역 상태로 테스트·추론 난해. 명시적 파라미터가 우월.

## R3 — 최근 스캔 이력 기록(선행 신설)

**현황**: 스캔 이력 테이블은 KB-90 에서 삭제됨(`V2026.07.09.21.45.01__drop_menu_scan_tables.sql` — "스캔 내역 미기록" 결정). 현재 `ScanUseCase.assessMenuBoard` 는 memberId 없이 요청당 판정만 하고 저장 안 함. `:core:scan` 모듈은 settings.gradle.kts 에 미포함(문서상 예약만).

**Decision**: `:core:scan` 컨텍스트를 신설(헌법·CLAUDE.md 가 예약해 둔 자리):
- `ScanHistory` `@AggregateRoot` — `memberId: Long`, `foodId: Long`, `scannedAt`(영속에선 BaseEntity `createdAt` 재사용). 팩토리 `record(memberId, foodId)`.
- port `ScanHistoryRepository` — `saveAll(records)`, `findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long>`.

영속 `scan_history`(신규 Flyway, timestamp 버전): `member_id`·`food_id` + BaseEntity 공통 컬럼. `findRecentReadyFoodIds` 는 네이티브로 **dedup(같은 food 최신 1건)·`created_at` 내림차순·READY 음식 join·limit** 을 한 번에:
```sql
SELECT sh.food_id
FROM scan_history sh
JOIN food f ON f.id = sh.food_id AND f.status='ACTIVE' AND f.content_status='READY'
WHERE sh.member_id = :memberId AND sh.status='ACTIVE'
GROUP BY sh.food_id
ORDER BY MAX(sh.created_at) DESC
LIMIT :limit
```
`ScanUseCase` 는 매칭된 **READY 음식(foodId non-null & isReady)** 만, **memberId != null 일 때만** 기록한다. 외부(정제 LLM) 호출 뒤 단발 write 라 트랜잭션을 길게 잡지 않음(헌법 Additional Constraints).

**Rationale**: 스캔 이력은 member·food 어디에도 속하지 않는 독립 개념 → 별도 컨텍스트가 헌법 II 에 맞음. dedup·정렬·READY 필터를 SQL 로 처리해 앱 로직 최소화. `scanned_at` 별도 컬럼 없이 `created_at` 재사용(중복 상태 컬럼 회피).

**Alternatives**: (a) member/food 모듈에 얹기 → 컨텍스트 오염. (b) 앱 메모리에서 dedup → 불필요 로딩. SQL dedup 우위.

## R4 — 인기 음식 무작위 5개

**현황**: 인기도 지표(조회수·스캔수) 미집계. `FoodRepository.findFoodPage(cursor, size)` 는 커서 기반 READY 목록. 무작위 조회 메서드 없음.

**Decision**: `FoodRepository.findRandomReady(size): List<Food>` 신설 — 영속 어댑터가 네이티브 `SELECT id FROM food WHERE status='ACTIVE' AND content_status='READY' ORDER BY RAND() LIMIT :size` 로 id 뽑고 기존 `findByIdInWithAvoidanceSubstances` 로 로드. 응답은 `FoodSummaryView` 리스트(회원 기피 기준 위험도 포함). READY 음식이 5개 미만이면 있는 만큼.

**Rationale**: 지표 도입 전 임시 규칙. 응답 계약(`FoodSummaryView`)이 고정이라 나중에 `ORDER BY popularity` 로 쿼리만 교체해도 클라이언트 무영향(spec SC-006).

**Ceiling / upgrade path**: `ORDER BY RAND()` 는 풀스캔 정렬이라 대용량에서 비효율 — 현 카탈로그 규모(소규모)에서 허용. 인기도 컬럼/집계가 생기면 정렬 기준만 교체(마이그레이션 아님, 쿼리 교체). Flyway/쿼리 근거는 plan·research 에 남긴다(Kotlin 주석 금지).

**Alternatives**: 앱에서 전체 id 로드 후 셔플 → 메모리 낭비. `RAND()` 가 5개엔 단순·충분.

## R5 — 언어 결정 규칙

**현황**: 기존 food 유스케이스는 `LanguageResolver` 로 미지정 시 `ko` 폴백. 홈 spec 은 비회원·미완료 회원 = **영어** 기준.

**Decision**: 홈 유스케이스 전용 규칙 — `lang = member?.profile?.appLanguage ?: LanguageCode.EN`. 회원이라도 `appLanguage` 가 null(온보딩 미완료)이면 `EN`. 음식 이름은 `Food.displayName(lang)`, 기피 성분 이름은 `AvoidanceSubstance.displayName(lang)`(둘 다 번역 부재 시 `LocalizedText` 의 ko 폴백) 재사용.

**Rationale**: 외국인 대상 서비스에서 비회원 기본은 한국어보다 영어가 적절(spec 명시). 기존 폴백 인프라 재사용으로 신규 로직 없음.

## R6 — 홈 응답에서 회원/비회원 "없음" 구분

**Decision**: 비회원 = 개인화 섹션 `null`(avoidedSubstances·recentScans), 회원 = 설정/이력 없음이면 `[]`. `HomeResult(avoidedSubstances: List<AvoidedSubstanceView>?, popularFoods: List<FoodSummaryView>, recentScans: List<FoodSummaryView>?)`.

**Rationale**: 클라이언트가 "로그인 안 함(null)" vs "회원이지만 비어 있음([])" 을 구분해 로그인 유도/온보딩 분기 가능(spec Edge Case).

## R7 — 기피 성분 섹션 payload = 코드 + 지역화 이름

**현황**: `AvoidanceSubstanceRepository.findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>` 존재, `AvoidanceSubstance.displayName(lang)` 로 지역화 이름 제공.

**Decision**: `AvoidedSubstanceView(code: String, name: String)` — 회원 프로필의 코드 집합을 `AvoidanceSubstanceCode` 로 변환 → `findByCodes` 로 지역화 이름 조회 → 뷰 생성. member 의 `AvoidanceSubstanceCodeRef.value` → enum 변환은 application 조합 계층에서(헌법 II: 코드로만 참조).

**Rationale**: spec SC-002(회원 응답 100% 프로필 언어)를 기피 성분 이름까지 만족. 기존 avoidance 읽기 port 재사용 → 신규 영속 없음.
