# Phase 0 Research: 메뉴 스캔 메뉴명 정제

핵심 결정과 근거. 구현 중 뒤집힌 결정은 **이력**으로 남긴다(왜 그렇게 안 했는지가 다음 사람에게 필요하다).

## D1 — 정규화 규칙과 역할

**Decision**: 매칭 키 = NFC 정규화 후 **한글 음절(`[가-힣]`)만** 남긴 문자열. 순수 함수 `KoreanMenuNameNormalizer.matchKey(raw)`를 `:core:kernel`에 둔다(scan 매칭·food 생성 컬럼이 공유하는 vocabulary).

역할은 둘뿐이다:
1. **비한글 pre-filter** — 빈 키(`6,500`, `MacBook Air F9`)는 LLM에 보내지 않고 결과에서 제외.
2. **매치 키 빌더** — LLM 표준명과 폴백 원문 양쪽을 같은 규칙으로 키화.

**정규화 결과의 "음식 여부"는 판정하지 않는다.** `김치찌개`·`원산지중국`·`왕김치찌개`는 형태로 구분 불가다. 판정자는 **DB exact 매치(hit=아는 음식)** 또는 **LLM(의미)**이다.

**Rationale**: 순수·Spring-free. 공백 제거로 `돼지 국밥`/`돼지국밥` 변형을 흡수.

## D2 — food 매칭 키 저장·조회

**Decision**: `food`에 MySQL **생성 저장 컬럼**
```sql
korean_match_key VARCHAR(255)
  GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name COLLATE utf8mb4_bin, '[^가-힣]', '')) STORED
```
+ 인덱스. 도메인 port는 **배치 조회** `findByKoreanMatchKeys(keys): Map<String, Food>`.

- 생성 컬럼이라 기존/신규 row 자동 계산 — 백필·write 경로 변경 불필요.
- kernel `matchKey`와 SQL 식의 동등성을 표본 sync 테스트(Testcontainers)로 고정.

**⚠️ collation 함정 (실측으로 발견)**: `[^가-힣]` 문자 범위는 MySQL 기본 collation(`utf8mb4_0900_ai_ci`)에서 정렬 순서로 해석돼 `range x comes after y` 에러로 **마이그레이션이 실패**한다. `COLLATE utf8mb4_bin`(코드포인트 순서)이 필수다. Testcontainers는 collation이 달라 이 결함을 잡지 못했고, **로컬 docker MySQL에 실제 마이그레이션을 돌려서** 발견했다.

**동음이의**: `korean_name`엔 UNIQUE가 있어 이름 중복은 없지만, 서로 다른 이름이 같은 정규화 키가 될 수 있다(`국밥`/`국 밥`). 이때 **최소 id 매칭 + 경고 로깅**.

**Alternatives**: 앱이 write 시 컬럼 채우기(백필 필요) 기각. 항목별 개별 조회 → 위험도 산출에 전체 애그리거트가 필요해 100항목이면 100 fetch join. 배치 조회로 대체.

## D3 — LLM 정제 (동기 1콜)

**Decision**: `:core:kernel` port `ScannedNameInterpreter.interpret(texts): List<InterpretedName>`, `InterpretedName = StandardName(korean) | NotFood`. `:infra:llm`에 **단일 Upstage caller** 어댑터(`solar-pro`, `temperature=0`, 타임아웃 5s). `:app:api`가 `runtimeOnly` 조립, `@ConditionalOnProperty(meogo.llm.upstage.enabled)`.

**응답 형식: 입력과 같은 길이의 문자열 배열 + `"NOT_FOOD"` 센티넬.**

왜 "음식만 골라 반환"이 아닌가:
- 결과를 `itemId`로 되짚으려면 **위치 정렬**이 필요하다. 같은 `rawMenuName`에 다른 `itemId`가 허용되므로(공기밥 두 번) 내용 기반 매칭은 불가능하다.
- 인덱스를 echo하는 방식(`[{"i":0,...}]`)은 모델이 인덱스를 틀리면 **조용히 엉뚱한 항목에 붙는다**. 같은 길이 방식은 **길이 불일치로 즉시 감지**되어 폴백으로 떨어진다. 안전 서비스에서 조용한 오매칭이 최악이다.

**프롬프트(영문)**: system에 hard rule(같은 길이·순서 유지·코드펜스 금지) + 중간 슬롯이 `NOT_FOOD`인 few-shot 1개. user에 기대 개수 명시 + 입력을 번호로 제시. `temperature=0`으로 결정적 판정.

**실측 검증**: `solar-pro` 실호출로 로마자 제거·오탈자 교정(`된장찌게`→`된장찌개`)·비음식 판정·코드펜스 없는 순수 JSON 배열을 확인했다. (배치 쪽 `solar-pro3`는 malformed JSON으로 롤백된 이력이 있어 형식을 단순하게 유지했다.)

## D4 — miss 처리: 대기열 → in-place 미완성 food (**결정 변경**)

**Decision**: 매칭되지 않은 표준명은 **`food` 테이블에 `content_status=INCOMPLETE`로 직접 등록**한다(`createIncomplete`, get-or-create). 레시피·설명·번역이 채워져 `READY`가 되어야 일반 조회에 노출된다.

**이력**: 초기 설계는 별도 `pending_menus` 대기열 테이블이었다. 구현 후 **폐기**했다 — miss가 곧 "조사 대상 음식"이므로 별도 큐가 중복이고, 배치는 `food WHERE content_status=INCOMPLETE`를 스캔하면 된다. 대기열 인프라(테이블·엔티티·repo·어댑터·port·마이그레이션) 한 겹이 통째로 사라졌다.

**serving gate는 목록·검색 쿼리 + 상세 어댑터에만 건다.** 스코어링 배치가 공유하는 `findByIdInWithAvoidanceSubstances`에 걸면 배치가 미완성 음식을 못 봐서 **영영 채워지지 않는다**.

`LocalizedText.korean`이 blank를 금지하므로 미완성 음식의 description은 플레이스홀더를 넣는다(serving gate로 노출되지 않음).

## D5 — 위험도 산출 (mock 제거)

**Decision**: `MockCyclingRiskAssessor` 삭제. 유스케이스가 사용자 회피 코드로 `Food.overallRisk()`를 직접 호출한다. 회원 기능 전까지 `MockAvoidedSubstanceProvider`가 회피 코드를 준다.

초기엔 `FoodRiskEvaluator` 컴포넌트로 감싸고 카탈로그 활성 코드와 교집합을 냈으나, develop 머지 시 KB-62 가 정한 규칙(**카탈로그 상태를 보지 않는다** — 카탈로그는 고정이며 소프트삭제되지 않는다, `specs/kb-62-menu-search/contracts/menu-search-api.md`)으로 통일하며 둘 다 걷어냈다. 남겨 뒀다면 스캔만 다른 위험도 규칙을 쓰게 된다.

**⚠️ 함정**: `RiskLevel.aggregate(빈 목록) = SAFE`. 미완성 음식은 성분이 비어 있어 그냥 계산하면 **"안전"으로 나온다.** 그래서 가드를 **도메인 안**에 뒀다:
```kotlin
fun overallRisk(avoidedCodes: Set<...>): RiskLevel {
    if (!isReady()) return RiskLevel.UNKNOWN
    ...
}
```
호출자가 잊을 수 없는 자리. 판정 기준은 성분 유무가 아니라 **콘텐츠 완성 상태**다.

## D6 — 폴백과 degraded

**Decision**: interpreter 미구성·예외·타임아웃·**응답 개수 불일치**면 정규화 exact 매치 폴백으로 강등한다. 스캔은 항상 성공한다.

- **폴백은 미완성 음식을 만들지 않는다.** 음식 여부 판정이 없으므로 `원산지중국` 같은 잡음이 사용자 데이터 테이블을 오염시킨다.
- 폴백에선 비음식을 걸러낼 수 없어 결과에 섞인다 → 응답에 **`degraded=true`**를 실어 클라이언트가 알 수 있게 한다.
- "해석할 항목이 아예 없었던 경우"(전부 비한글)는 강등이 아니다(`degraded=false`).

**Alternatives**: LLM 필수·폴백 제거(외부 장애=기능 정지) 기각. degraded 표시 없이 폴백(계약이 조용히 깨짐) 기각.

## D7 — 스캔 내역·바운딩 박스 제거 (**결정 변경**)

**Decision**: 스캔은 요청당 정제·매칭·판정만 하고 응답한다. `menu_scan`·`scanned_menu_item` 테이블과 `MenuScan` 애그리거트·영속을 전부 제거하고, 요청에서 바운딩 박스를 받지 않는다.

- `create_scan_tables.sql`은 develop에 이미 머지·적용돼 파일 삭제가 금지되므로(CLAUDE.md) **DROP 마이그레이션**으로 되돌렸다.
- `itemId` 중복 400 검증은 `MenuScan` 애그리거트에 있었으므로 **요청 DTO(`@AssertTrue`)로 이관**했다.
- 응답에서 `scanId`·항목 `id`·`reason`이 사라졌다.

## 후속 (범위 밖)

- **회원 기능** 도입 시 `MockAvoidedSubstanceProvider` → 실제 사용자 프로필로 교체.
- **조사 배치**: `food WHERE content_status=INCOMPLETE`를 소비해 레시피·설명·번역을 채우고 `READY`로 전이.
- **NFC 불변식**: `food.korean_name`은 항상 NFC로 저장돼야 kernel 규칙과 SQL 생성 컬럼의 키가 일치한다(sync 테스트가 방어선).
- 정제 결과 캐시 없음(잡음 문자열 롱테일이라 이득이 적음 — 사용자 결정).
