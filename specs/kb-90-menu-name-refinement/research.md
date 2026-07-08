# Phase 0 Research: 메뉴 스캔 메뉴명 정제

정제·매칭 설계의 핵심 결정. **주 경로 = 정규화 → 전부 LLM 음식명 추출 → DB 매칭 → hit/miss → miss 대기열.** LLM 장애 시에만 정규화 exact 매치로 폴백한다(사용자 결정 2026-07-08).

## D1 — 정규화 규칙·위치·역할

**Decision**: 매칭 키 = 입력 텍스트를 NFC 정규화 후 **한글 음절(`[가-힣]`)만 남긴 문자열**. 순수 함수 `KoreanMenuNameNormalizer.matchKey(raw): String` 를 **`:core:kernel`** 에 둔다.

새 흐름에서 정규화기의 역할은 두 가지:
1. **비한글 pre-filter** — `matchKey(raw)` 가 빈 문자열이면(한글 0자: "MacBook Air F9", "6,500") 그 항목은 **LLM 에 보내지 않고 곧장 NOT_FOOD** 처리(불필요한 LLM 토큰·비용 절감).
2. **매치 키 빌더** — (a) LLM 이 돌려준 표준 한국어 이름을 DB exact 매치할 때, (b) 폴백에서 원문을 exact 매치할 때, 양쪽 다 이 함수로 키를 만든다(동일 규칙 재사용).

**정규화 결과의 "깨끗함"은 독립 판정하지 않는다** — 한글만 남은 문자열이 음식명인지("김치찌개")·비음식인지("원산지중국")·수식어 변형인지("왕김치찌개")는 형태로 구분 불가(사용자 질의). 판정자는 **DB exact 매치(hit=아는 음식) 또는 LLM(의미)** 이다.

**Rationale**: 순수·Spring-free(kernel 제약). 빈 키 pre-filter 로 명백한 잡음의 LLM 호출을 없앰. 매치 키를 한 함수로 통일해 LLM 출력·폴백 원문이 같은 규칙으로 매칭됨.

## D2 — foods 매칭 키 저장·조회

**Decision**: `foods` 에 **MySQL 생성 저장 컬럼** `korean_match_key VARCHAR(255) GENERATED ALWAYS AS (REGEXP_REPLACE(korean_name,'[^가-힣]','')) STORED` + 인덱스. 도메인 port `FoodRepository.findByKoreanMatchKey(key): List<Food>`. 주 경로(LLM 출력 매칭)·폴백(원문 매칭) 양쪽이 이 조회를 쓴다.

- 생성 컬럼이라 기존/신규 row 자동 계산 — 백필·write 경로 변경 불필요.
- kernel `matchKey` 규칙 == SQL `REGEXP_REPLACE(...,'[^가-힣]','')` 동등성을 표본 sync 테스트(Testcontainers)로 고정([[migration-filename-test-coupling]] 교훈).

**동음이의**: `korean_name` 은 유일하지 않음 → `List` 반환. 1개=MATCHED, 0개=miss, 2개↑=최소 id 매칭+경고 로깅(위험도 mock 인 동안 안전 영향 없음, de-mock 시 재검토).

**Alternatives**: 앱이 write 시 컬럼 채우기(백필 필요) 기각(YAGNI). in-memory 전량 스캔(규모 미정 O(N)) 기각.

## D3 — LLM 음식명 추출 (주 경로)

**Decision**: `:core:kernel` port `ScannedNameInterpreter.interpret(texts: List<String>): List<InterpretedName>`, `InterpretedName = StandardName(korean) | NotFood`(sealed). `:infra:llm` 에 **단일 Upstage `LlmModelCaller`** 어댑터 `UpstageScannedNameInterpreter`(3모델 fanout 은 배치 전용, 미재사용). **빈 키가 아닌 전 항목**을 스캔당 **1콜**(배열 입출력)로 보내 표준 한국어 메뉴명 또는 NOT_FOOD 를 받는다. `:app:api` `runtimeOnly` 조립. `@ConditionalOnProperty(meogo.llm.upstage.*)` 게이팅.

- LLM 입력은 **원문 텍스트**(로마자 음역 등 문맥이 추출에 도움) — 정규화기는 어느 항목이 LLM 대상인지 거르는 게이트로만 쓰고, LLM 이 볼 텍스트를 미리 뭉개지 않는다. (사용자 표현 "정규화 거친 후 LLM": 정규화 게이트를 통과한 항목이 LLM 으로 간다는 의미로 구현.)
- 프롬프트 system 역할: "각 텍스트에서 표준 한국어 메뉴명을 추출. 음식이 아니면 NOT_FOOD." 배열 JSON 입출력, 입력 순서 1:1. 파서는 research `ScoringResponseParser` 패턴.
- 총 타임아웃 예산 ~2s. 실패·타임아웃·부분 파싱 실패는 예외 → D4 폴백.

**Rationale**: 오탈자·미등록 신메뉴·수식어·비음식 판정은 의미 이해라 규칙 불가. 사용자가 "전부 LLM 으로 음식명 추출" 을 택함 — 매칭을 LLM 출력 기준으로 단일화(정규화 exact 매치는 폴백으로 강등).

**Alternatives**: 정규화 exact 매치를 주 경로로 두고 잔여만 LLM(이전 설계) → 사용자가 단일 LLM 경로로 변경. 3모델 fanout → 과함, 기각.

## D4 — 라우팅·대기열·폴백

**주 경로 (LLM 정상)**:
```
각 항목 raw → matchKey(raw)
  빈 키(한글 0)         → NOT_FOOD (LLM 스킵)
  비어있지 않음         → LLM 대상 수집
LLM interpret(대상들) 1콜:
  StandardName(korean) → matchKey(korean) 로 foods exact 매치
                          hit  → MATCHED(foodId)
                          miss → PENDING + enqueue(표준명)
  NotFood              → NOT_FOOD (대기열 미등록)
```

**폴백 (LLM 미구성·실패·타임아웃)**:
```
LLM 대상 전원 → matchKey(raw) 로 foods exact 매치
  hit  → MATCHED(foodId)       (아는 메뉴는 장애 중에도 살아있음)
  miss → PENDING + enqueue(원문) (신메뉴/수식어/비음식 구분 불가 → LLM 복구 후 처리)
```

- **스캔 항목 상태** `MenuItemMatch`: `MATCHED(foodId)` / `PENDING` / `NOT_FOOD`. (UNMATCHED 중간상태 불요 — 주 경로든 폴백이든 항상 3종 중 하나로 종결.) `ScanStatus` 는 `COMPLETED` 유지.
- **대기열** `pending_menus`: 표준명(폴백 시 원문) unique dedup, port `PendingMenuRepository.enqueue`. blank 거절. INSERT ON DUPLICATE KEY no-op. 소유 컨텍스트 `:core:scan`.
- **트랜잭션 경계**: LLM 호출을 트랜잭션 밖에서(Additional Constraints). 스캔 저장(pending) → LLM 호출 → 결과로 항목 상태·대기열 확정 저장.

**Rationale**: exact 매치가 "깨끗함" 판정을 대신하므로 폴백에 휴리스틱이 없다(hit 이면 아는 음식, 아니면 대기열). 아는 메뉴는 LLM 장애에도 폴백 exact 매치로 계속 매칭 — 사용자가 우려한 "핵심 기능 종속"을 완화. unique 제약 dedup 이 가장 단순·정확(SC-005).

**Alternatives**: LLM 실패 시 전량 대기열(폴백 없음) → 아는 메뉴도 장애 중 못 됨. 기각. 스캔 자체 5xx → 핵심 기능이 LLM 에 완전 종속. 기각(사용자 우려 지점).

## 미해결/후속 (범위 밖)

- 위험도 산출은 mock(`MockCyclingRiskAssessor`) 유지 — 매칭된 food 기준 실제 위험도는 별도 작업.
- `pending_menus` 소비 레시피 조사 배치는 별도(이 작업은 적재·dedup·상태까지). **pending_menus 는 소프트삭제하지 않고 `queue_status`(PENDING/RESOLVED/REJECTED)로만 lifecycle 관리**한다(unique+@SQLRestriction 조합에서 소프트삭제 시 재등록 불능 방지 — upsert 는 `ON DUPLICATE KEY UPDATE status='ACTIVE'` 로 resurrect-safe).
- **N+1 후속**: 항목당 `findFoodIdByKoreanMatchKey` 개별 조회 → de-mock/프로덕션 전 `WHERE korean_match_key IN (:keys)` 배치 조회 1콜로 접기(현재 index point-lookup·mock 위험도라 비차단, 리뷰 지적).
- **NFC 불변식**: kernel `matchKey` 는 NFC 정규화 후 한글 필터하지만 MySQL 생성 컬럼은 NFC 미수행 — `food.korean_name` 은 항상 NFC 로 저장한다는 전제(write-side)에서 두 키가 일치한다. 동등성 sync 테스트가 방어선.
- 동음이의(match_key 충돌: 서로 다른 korean_name 이 같은 정규화 키)는 최소 id 매칭 — 위험도 de-mock 시 재검토(D2).
