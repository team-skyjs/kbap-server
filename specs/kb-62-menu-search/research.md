# Phase 0 Research: 검색어에 맞는 메뉴 조회 (다국어 부분 일치, no-offset)

모든 항목은 `develop`(7a769cf) 코드베이스 + MySQL 8.4 동작 근거로 해소했다. NEEDS CLARIFICATION 잔여 없음. 페이지네이션·위험도·언어·커서·빈결과 매핑은 KB-63 research(R1~R6)와 동일하므로 여기선 **검색 고유 항목만** 다룬다.

## R1. 요청 언어 번역명 매칭 — JSON 컬럼에서 어떻게 뽑나

- **Decision**: 번역명은 `food.name_translations` **JSON 컬럼**(`Map<langCode,String>`, 예: `{"en":"Bibimbap","ja":"..."}`)에 저장돼 있다(`FoodJpaEntity` `@JdbcTypeCode(JSON)`). 요청 언어 번역명 매칭은 `JSON_UNQUOTE(JSON_EXTRACT(name_translations, '$."<lang>"')) LIKE CONCAT('%', :kw, '%')` 로 한다. 최종 매칭 술어:
  ```sql
  korean_name LIKE CONCAT('%', :kw, '%')
  OR (:jsonPath IS NOT NULL
      AND JSON_UNQUOTE(JSON_EXTRACT(name_translations, :jsonPath)) LIKE CONCAT('%', :kw, '%'))
  ```
  `:jsonPath` 는 어댑터가 조립: `lang == KO` → `null`(한국어명만), 그 외 → `$."en"`·`$."zh-Hans"` 처럼 언어 코드를 큰따옴표로 감싼 경로 문자열(하이픈 코드 `zh-Hans` 안전).
- **Rationale**: 매칭 대상이 "한국어명 + 요청 한 언어 번역명"(사용자 정정)이라 JSON 전체 값 스캔(`JSON_SEARCH`)이 아니라 **요청 언어 키만 정확히 추출**해야 한다. `JSON_EXTRACT` 의 path 인자는 바인드 파라미터로 전달 가능(리터럴 강제 아님)해 언어별 쿼리 분기 없이 한 쿼리로 처리된다. `JSON_UNQUOTE` 로 따옴표 제거 후 LIKE.
- **Alternatives considered**: (a) `JSON_SEARCH(name_translations,'one',CONCAT('%',:kw,'%'))` — **모든 언어 값**을 훑어 "요청 언어만" 요건에 어긋남, 기각. (b) 번역을 별도 정규화 테이블로 풀어 `translation_name LIKE` — 스키마 변경·마이그레이션 유발, 현 JSON 저장 모델(KB-48)을 뒤집는 과도한 변경이라 기각. (c) 앱단에서 전량 로드 후 인메모리 필터 — 페이지네이션·성능 파탄, 기각.

## R2. 대소문자 비구분 — LOWER() 필요한가 (⚠️ 초안 전제 정정됨 — US1 DB 리뷰 실측)

- **Decision**: `LOWER()` 는 쓰지 않는다. 대신 **두 매칭 분기 모두에 `collate utf8mb4_unicode_ci` 를 명시**한다.
  ```sql
  f.korean_name collate utf8mb4_unicode_ci like ... escape ...
  or json_unquote(json_extract(f.name_translations, :jsonPath)) collate utf8mb4_unicode_ci like ... escape ...
  ```
- **초안 전제가 틀렸다 (정정)**: 초안은 "MySQL 8 기본 콜레이션 `utf8mb4_0900_ai_ci` 가 알아서 대소문자 비구분을 처리하므로 `collate` 불요"라고 적었다. 실측 결과 **둘 다 사실이 아니다**:
  1. `food.korean_name` 의 실제 콜레이션은 `utf8mb4_0900_ai_ci` 가 **아니라 `utf8mb4_unicode_ci`** 다 — `docker-compose.yml` 과 Testcontainers(`MySqlContainerConfig`)가 `--collation-server=utf8mb4_unicode_ci` 로 서버 기본값을 덮기 때문이다.
  2. `json_unquote(json_extract(...))` 의 반환 콜레이션은 컬럼 콜레이션을 물려받지 않고 **`utf8mb4_bin`** 이다 → `collate` 를 빼면 번역명 매칭이 **대소문자를 구분**한다(`bibim` 이 `Bibimbap` 에 매칭 안 됨). 구현 중 실제로 이 테스트 하나만 실패해 드러났다.
- **Rationale (콜레이션 이름 선택)**: `utf8mb4_unicode_ci` 는 (a) 로컬·Testcontainers 의 실제 서버/컬럼 콜레이션과 일치해 **두 분기의 비대칭을 없애고**, (b) **MySQL 5.7·8.x 모두에 존재**한다. 반면 `utf8mb4_0900_ai_ci` 는 8.0+ 전용이라, prod 가 5.7 이면 검색 쿼리가 런타임에 실패한다 — 그리고 로컬·CI 는 둘 다 8.4 라 **이 실패를 절대 잡지 못한다**. prod DB 는 저장소 밖 외부 컨테이너(`mysql-prod`, `docker-compose.prod.yml` 참고)라 버전이 기록돼 있지 않으므로, 이식성 있는 쪽을 택한다.
- **`korean_name` 에 `collate` 를 붙이는 비용**: 없다. leading-wildcard LIKE 는 어차피 어떤 B-tree 인덱스도 못 타므로(R3), 콜레이션 명시가 잃을 인덱스 이점이 애초에 없다. (`uq_food_korean_name` 은 `possible_keys` 에 오르지도 않는다 — US1 DB 리뷰 EXPLAIN 확인.)
- **`collate` 강제의 실효 (US1 DB 리뷰 실측)**: 현재 환경에선 **no-op** 이고, 서버 기본 콜레이션이 나쁠 때만 살아나는 **안전망**이다.

  | `korean_name` 컬럼 콜레이션 | `collate` 없이 | `collate` 강제 |
  |---|---|---|
  | `utf8mb4_unicode_ci` (현 로컬·Testcontainers) | 매칭 O | 매칭 O (no-op) |
  | `utf8mb4_0900_ai_ci` (prod 가 이럴 경우) | 매칭 O | 매칭 O (coercion legal, 에러 없음) |
  | `utf8mb4_bin` (최악의 서버 기본값) | **매칭 X** | **매칭 O** |

  즉 지금 동작을 바꾸지 않으면서 컬럼 콜레이션이 무엇이든 FR-003 을 결정적으로 보장한다. `0900_ai_ci` 컬럼에 `unicode_ci` 를 강제해도 같은 utf8mb4 charset 이라 `ER_UNKNOWN_COLLATION` 도 "Illegal mix of collations" 도 발생하지 않는다(OR 로 묶인 두 LIKE 는 서로 피연산자가 아니다).
- **⚠️ 승격 시 함정 (미래 트랩)**: 훗날 prefix 매칭(`LIKE 'kw%'`)으로 인덱스를 태우려 하면, **좌변 `collate` 강제가 range 최적화를 막는다**(컬럼과 다른 콜레이션으로 비교하면 옵티마이저가 인덱스 순서를 쓸 수 없다). 지금은 leading-wildcard 라 무해하지만, FULLTEXT/prefix 로 승격할 때는 쿼리의 `collate` 를 **빼고** 컬럼 콜레이션을 **DDL(Flyway)로 고정**하는 쪽으로 전환해야 한다.
- **Alternatives**: (a) `LOWER(col) LIKE LOWER(:kw)` — CJK 무의미, 악센트 비구분 상실, 콜레이션과 중복이라 기각. (b) `collate utf8mb4_0900_ai_ci` 유지 — 위 이식성 위험으로 기각. (c) Flyway 로 컬럼 콜레이션 고정 — 공유 DB 스키마 변경이라 KB-62 범위를 넘고, 쿼리 레벨 `collate` 로 충분해 기각.
- **잔여 리스크(사용자 확인 필요)**: prod `mysql-prod` 의 MySQL 버전·서버 콜레이션이 저장소에 기록돼 있지 않다. `utf8mb4_unicode_ci` 는 5.7·8.x 공통이라 안전하지만, prod 버전을 명시적으로 확인·기록해 두는 것이 옳다.

## R3. 성능·인덱스 — leading-wildcard LIKE 풀스캔 수용

- **Decision**: `LIKE '%kw%'`(중간 포함, leading wildcard)는 B-tree 인덱스를 못 탄다 → `food` 풀스캔. **신규 인덱스를 두지 않고** 풀스캔을 수용한다. keyset(`id < :cursor` + `LIMIT 21`)은 그대로 유지해 **페이지 깊이 불변 성능**(SC-003)을 만족한다.
- **Rationale**: SC-003 이 요구하는 건 "데이터 양·페이지 깊이에 따른 저하 없음(OFFSET 스캔 제거)"이지 매칭 자체의 인덱스 최적화가 아니다. 메뉴 카탈로그는 수천 행 규모라 풀스캔 비용이 실사용에서 문제되지 않는다. `_ci` 콜레이션 특성상 일반 인덱스는 어차피 infix LIKE 를 못 쓴다.
- **실측 확인 (US1 DB 리뷰)**: 커서 지정 시 `EXPLAIN` 이 `type=range key=PRIMARY, Backward index scan` — MySQL 이 `(:cursor is null or f.id < :cursor)` 를 상수 폴딩해 PK range 로 축약한다. **SC-003(깊이 불변) 실제 충족.** 다만 LIKE 는 `filtered≈10%` 의 행별 post-filter 라, 정확히는 "**페이지 깊이 불변**"이지 "카탈로그 크기 불변"은 아니다 — 카탈로그가 커지면 페이지당 스캔 행이 함께 늘어난다(이것이 R3 이 수용한 상한이다).
- **ponytail 상한/업그레이드 경로**: 카탈로그가 크게 늘어 검색이 느려지면 **FULLTEXT + ngram parser**(CJK 부분어)나 외부 검색엔진(OpenSearch)로 승격한다. 지금 도입은 과설계 — 네이티브 LIKE 로 시작하고 seam(어댑터 메서드) 뒤에 숨겨 교체 여지를 남긴다.
- **Alternatives**: 접두 인덱스 + `LIKE 'kw%'`(prefix-only) — "포함(부분 일치)" 요건(FR-003, 앞·중간·끝)과 배치라 기각. 생성 컬럼 + 인덱스 — JSON 언어별로 컬럼 폭증, 과설계 기각.

## R3a. 검색어의 LIKE 패턴 특수문자 — 이스케이프 (US1 코드 리뷰 발견)

- **Decision**: 검색어를 LIKE 패턴에 넣기 전에 **`\`·`%`·`_` 를 이스케이프**하고 쿼리에 `ESCAPE` 절을 명시한다. 이스케이프는 **`:infra:persistence` 어댑터의 책임**(LIKE 문법은 SQL 세부사항이라 도메인·유스케이스로 새면 안 된다).
- **Rationale**: 초안 구현은 `concat('%', :kw, '%')` 로 검색어를 그대로 패턴에 박아, `keyword=%` 가 **전체 메뉴**를 반환했다. 이는 단순 버그가 아니라 **역할 분리 위반**이다 — 스펙이 목록 API(KB-63)에 할당한 "검색어 없는 전체 탐색"을 검색 API 로 우회할 수 있게 된다(FR-003a 신설). `_` 는 임의 1문자 와일드카드라 미이스케이프 시 거의 모든 이름에 매칭된다. 파라미터 바인딩이라 SQL injection 은 아니지만, **의미론적 결함**이다.
- **자기 이스케이프 문자 처리**: 이스케이프 문자(`\`) 자체가 검색어에 있으면 먼저 이스케이프해야 한다(순서: `\` → `%` → `_`). 검색어에 `\` 를 넣는 회귀 테스트로 가드.
- **Alternatives**: (a) 이스케이프 없이 `%`·`_` 를 거절(400) — 정당한 검색어(`"할인 50% 세트"`)를 막아 기각. (b) 커스텀 ESCAPE 문자(`!`) — 백슬래시 이중화 혼란을 피하는 이점은 있으나, `ESCAPE` 절을 명시하면 어느 쪽도 동등하므로 구현 재량.
- **`collate` 와의 공존 (실측 확인)**: `COLLATE` 는 `LIKE` 보다 우선순위가 높아 좌변에 결합하고, `ESCAPE` 는 LIKE 절의 후행 수식어라 서로 간섭하지 않는다. `_bin` 컬럼에 둘을 함께 걸어도 대소문자 비구분이 복구된다.
- **알려진 상한**: `escape '\\'` 는 서버 `sql_mode` 에 `NO_BACKSLASH_ESCAPES` 가 **없다는 전제**에 의존한다(현 서버 실측상 없음). 켜지면 `Incorrect arguments to ESCAPE` 로 검색이 실패한다. 발생 시 escape 문자를 `!` 등으로 바꾸면 되므로 지금 방어 코드는 두지 않는다.
- **이스케이프는 sargability 를 바꾸지 않는다**: `ESCAPE` 는 패턴 해석만 바꾼다. 패턴이 이미 leading-wildcard 라 처음부터 non-sargable 이었다(EXPLAIN 확인).

## R4. 네이티브 쿼리의 소프트삭제 필터 — @SQLRestriction 손실 보정

- **Decision**: 검색 id 쿼리를 **네이티브 SQL**(`@Query(nativeQuery = true)`)로 쓴다. `@SQLRestriction("status='ACTIVE'")`(BaseEntity)는 **JPQL/엔티티 로드에만** 적용되고 네이티브 SQL 엔 안 붙으므로, 검색 쿼리에 `AND status = 'ACTIVE'` 를 **명시**해 소프트삭제 메뉴를 제외한다(FR-015). 2단계 fetch join(`findByIdInWithAvoidanceSubstancesDesc`)은 기존 JPQL 이라 `@SQLRestriction` 이 여전히 적용된다.
- **Rationale**: 요청 언어 JSON 추출은 JPQL 표준 함수로 이식성 있게 쓰기 어렵다(Hibernate JSON HQL 함수는 버전·DB 의존). 네이티브가 명확·안전하다. 대신 ACTIVE 필터 자동성이 빠지는 **알려진 함정**을 명시 조건으로 메운다.
- **검증**: 어댑터 Testcontainers 슬라이스에 "소프트삭제된 food 는 검색 결과에서 제외" 케이스를 넣어 회귀 차단.
- **Alternatives**: JPQL + Hibernate `json_value`/`sql()` 함수 — 버전 결합·불투명이라 기각. 네이티브인데 status 조건 누락 — 삭제 메뉴 노출 버그, 반드시 회피.

## R5. 검색 id → 도메인 로드 (2단계 유지)

- **Decision**: KB-63 의 2단계 패턴 계승 — ① 네이티브 검색 id 쿼리(keyset, 컬렉션 미조인)로 `List<Long>` → ② 기존 `findByIdInWithAvoidanceSubstancesDesc(ids)`(JPQL fetch join, id desc)로 로드 후 `toDomain()`. 페이지당 라운드트립 2회 고정.
- **Rationale**: ①에서 `LIMIT` 이 확정된 id 만 넘기므로 ②의 컬렉션 fetch join 이 인메모리 페이징(HHH000104) 위험 없이 안전(KB-63 R2 와 동일 근거). ②를 재사용해 신규 로드 쿼리 불요.
- **Note**: ①이 이미 `ORDER BY id DESC LIMIT`(네이티브라 `Pageable` 대신 `LIMIT :size` 직접) 로 정렬·개수 확정. 어댑터는 `size = PAGE_SIZE + 1`(21) 를 넘겨 유스케이스가 hasNext 판정.

## R6. 빈/공백 검색어 — 실패 매핑 & 목록과의 분리

- **Decision**: 검색어 trim 후 공백이면 검색을 수행하지 않고 **`FoodErrorCode.BLANK_SEARCH_KEYWORD(400)`** 로 실패(`BaseResponse.fail(message)`). `SearchKeywordResolver.resolve(raw): String`(resolveCursor 형제)에서 trim + blank 검사. 컨트롤러/유스케이스 진입에서 호출.
- **Rationale**: 검색어 없는 전체 탐색은 이미 KB-63 `GET /api/v1/foods` 목록 API 가 담당한다. 검색 엔드포인트가 빈 검색어를 조용히 전체 조회로 처리하면 두 API 책임이 겹치고 의도 불명확 — 원칙 V 의 fail-fast 정신에 맞게 명시적으로 거절한다(FR-011). `GlobalExceptionHandler` 가 `MeogoException` 을 `ErrorCode.status`(400)로 이미 매핑.
- **Alternatives**: 빈 검색어 → 200 빈 목록 — 오류를 숨겨 클라이언트 디버깅 저해, 목록 API 와 책임 중복이라 기각. 빈 검색어 → 전체 목록 위임 — 엔드포인트 책임 혼선, 기각.

## R7. 유스케이스 중복 — browse vs search 뷰 조립

- **Decision**: `SearchMenusUseCase` 는 `BrowseMenusUseCase` 와 **페이지 소스(searchMenuPage vs findMenuPage)만 다르고** 이후 "회피 조달 + 카탈로그 필터 + `overallRisk` + `MenuSummaryView` 매핑"이 동일하다. 이 조립부(~25줄, 안전 직결 위험도)를 `MenuSummaryAssembler` 컴포넌트로 추출해 두 유스케이스가 공유하는 것을 **권장**한다.
- **Rationale**: 실재하는 순수 중복 제거라 투기적 추상화(원 impl 하나뿐인 인터페이스 등)가 아니다. 위험도 계산 의미가 목록·검색·상세에서 어긋나면 안전 문제가 되므로 단일 지점이 낫다. 단, TDD 흐름상 우선 인라인 복제로 Green 후 Refactor 단계에서 추출해도 무방(무리한 선추상 금지).
- **Alternatives**: 완전 별도 복제 유지 — 위험도 로직 이중 관리라 장기 위험. 상속/템플릿메서드 — 과설계, 기각(단순 컴포넌트 위임으로 충분).

## 재사용으로 해소된 항목 (KB-63 근거 계승 — 재조사 불요)

| 항목 | 결론(요약) | 근거 |
|------|-----------|------|
| keyset vs offset | `id < :cursor ORDER BY id DESC LIMIT 21` | KB-63 R1 |
| 커서 표현 | 평문 숫자(마지막 foodId), `Page.nextCursor: Long?` | KB-63 R3 |
| overallRiskStatus 계산 | 회피 조달 1회 + 카탈로그 일괄 1회, `overallRisk(avoided ∩ catalog)` | KB-63 R4 |
| 언어 폴백/거절 | `LanguageResolver`(원칙 V): 미지정/부재 ko, 미지원 400 | KB-63 R5 |
| 잘못된 커서 | `resolveCursor` → `INVALID_CURSOR(400)` | KB-63 R6 |
| 응답 봉투/경로 | `BaseResponse<Page<MenuSummaryResponse>>`, `/api/v1` | KB-63 data-model |
