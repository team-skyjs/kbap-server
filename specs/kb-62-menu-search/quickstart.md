# Quickstart: 검색어 메뉴 조회 (다국어 부분 일치, no-offset)

## TDD 순서 (원칙 I — Red → Green → Refactor)

계층 안쪽 → 바깥으로. 각 단계는 **실패 테스트 먼저** 작성·Red 확인 후 최소 구현. 페이지네이션·위험도·언어·커서는 KB-63 과 동일하므로, 검색 고유(**키워드 매칭·언어 분리·빈 검색어**)를 집중 검증한다.

1. **유스케이스 단위** — `SearchMenusUseCaseTest` (페이크 `FoodRepository`·`AvoidedSubstanceProvider`·`AvoidanceSubstanceRepository`).
   - given 빈/공백 `keyword` → `BLANK_SEARCH_KEYWORD` 예외(FR-011), `searchMenuPage` 미호출.
   - given `keyword=" 김치 "` → trim 되어 `searchMenuPage("김치", …)` 위임.
   - given 21개 반환 → `hasNext=true`, `nextCursor=items.last().foodId`, 20개 trim.
   - given 커서 지정 → `searchMenuPage(keyword, lang, cursor, 21)` 호출·최신순 유지.
   - given 결과 0개 → `items:[]`·`hasNext:false`·`nextCursor:null`.
   - given 미지원 lang → 예외(원칙 V); 미지정 → ko(한국어명만 매칭 위임 확인: lang=KO 전달).
   - given 회피 ∩ 성분 → food 별 `overallRiskStatus` 정확(browse 와 동일 조립).

2. **영속 어댑터 슬라이스** — `FoodRepositoryAdapterTest` 보강 (MySQL Testcontainers, seed: 한국어명·번역명 JSON 다건).
   - **한국어명 매칭**: `searchMenuPage("김치", KO, null, 20)` → 한국어명에 "김치" 포함만.
   - **번역명 매칭**: 영어 번역명 "Bibimbap" seed → `searchMenuPage("bibim", EN, null, 20)` 매칭(대소문자 비구분, R2).
   - **언어 분리**: 일본어 번역명만 매칭되는 seed 를 `lang=EN` 으로 검색 → 미포함(FR-004, R1).
   - **ko 폴백 매칭 범위**: `lang=KO` 는 번역명 무시, 한국어명만.
   - **keyset 경계**: `searchMenuPage(kw, lang, cursorId, 20)` 는 `id < cursorId` 매칭만.
   - **빈 결과**: 미포함 키워드 → `[]`.
   - **소프트삭제 제외**: 삭제된 food 는 매칭돼도 결과 제외(네이티브 `status='ACTIVE'`, R4) — **회귀 필수 케이스**.

3. **web 통합** — `MenuSearchControllerTest` (MockMvc, `@SpringBootTest`).
   - 부분 일치: `?keyword=…` 200·매칭 항목·`BaseResponse.success=true`.
   - `lang=en` 번역명 매칭·표시명 지역화.
   - 다음 커서로 연속 조회(같은 keyword) → 중복 foodId 없음(불변식 4).
   - 결과 없음 → 200 빈 목록 / 마지막 페이지 `hasNext:false`.
   - **빈/공백 keyword → 400** `success:false`(FR-011, 200 아님).
   - 잘못된 커서 → 400 / 미지원 lang → 400.

## 검증 커맨드

```bash
# 단위 (유스케이스)
./gradlew :application:client:test --tests "com.meogo.application.client.food.usecase.SearchMenusUseCaseTest"

# 영속 (Testcontainers — Docker 필요)
./gradlew :infra:persistence:test --tests "com.meogo.infra.persistence.food.FoodRepositoryAdapterTest"

# web 통합
./gradlew :app:api:test --tests "com.meogo.app.api.food.MenuSearchControllerTest"

# 경계(모듈 의존 방향) 무손상 확인
./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"

# 전체
./gradlew build
```

## 수동 확인 (로컬)

```bash
# 로컬 docker MySQL + 앱 (IntelliJ 실행 중이면 8080 점유 주의 — 새로 띄우지 말 것)
# SPRING_PROFILES_ACTIVE=local

# 한국어명 검색
curl "http://localhost:8080/api/v1/foods/search?keyword=김치"

# 영어 번역명 검색 (대소문자 무관)
curl "http://localhost:8080/api/v1/foods/search?keyword=bibim&lang=en"

# 다음 페이지 (위 응답 nextCursor, 같은 keyword)
curl "http://localhost:8080/api/v1/foods/search?keyword=김치&cursor=<nextCursor>"

# 빈 검색어 → 400
curl "http://localhost:8080/api/v1/foods/search?keyword="
```

Swagger UI: `http://localhost:8080/swagger-ui/index.html` — "음식 검색" 태그.

## 주의 / 함정

- **네이티브 쿼리 = ACTIVE 필터 수동**: 검색 id 쿼리는 네이티브라 `@SQLRestriction` 이 안 붙는다 → `AND status = 'ACTIVE'` 명시 필수. 빠뜨리면 삭제 메뉴가 검색에 노출됨(R4, 회귀 테스트로 강제).
- **JSON path 조립**: `lang=KO` → `jsonPath=null`(한국어명만). 그 외 → `$."${lang.code}"`. 하이픈 코드(`zh-Hans`)는 큰따옴표 포함해야 안전.
- **매칭은 요청 언어 한 개만**: 전 언어 동시 매칭 아님(사용자 정정). `korean_name` + `lang` 번역명 두 개만.
- **대소문자 비구분은 콜레이션**: `utf8mb4_0900_ai_ci` 가 처리. `LOWER()` 감싸지 말 것(R2).
- **인덱스 없음/풀스캔 수용**: leading-wildcard LIKE. 신규 인덱스·마이그레이션 **추가 금지**(R3). 카탈로그 커지면 FULLTEXT+ngram 으로 승격(seam=어댑터 메서드).
- **컬렉션 fetch-join + limit 금지**: 2단계 유지(검색 id → 기존 `findByIdInWithAvoidanceSubstancesDesc` 재사용). 정렬 `id desc` 일관.
- **응답 DTO 재사용**: `Page`·`MenuSummaryResponse` 신규 금지(KB-63 공유 스키마). 결과 DTO 도 `BrowseMenusResult` 재사용.
- **위험도 로직 단일 출처 권장**: browse 와 동일 조립 → `MenuSummaryAssembler` 추출(Refactor 단계 허용).
- **경로 충돌 회피**: 검색은 `/api/v1/foods/search`(목록 `/api/v1/foods` 와 별도).
- **신규 마이그레이션 없음** · **Kotlin 주석 금지**(고정) · **BehaviorSpec 한국어 given/when/then**(고정).
