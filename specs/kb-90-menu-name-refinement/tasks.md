---
description: "Delivered scope for 메뉴 스캔 메뉴명 정제"
---

# Tasks: 메뉴 스캔 메뉴명 정제 · 매칭 · 위험도 판정

**Status**: 전량 완료 (PR #41). 구현 중 설계가 두 번 바뀌어(대기열 폐기 → in-place 미완성 food, 스캔 기록·바운딩박스 제거) 아래는 **최종 인도 범위**다. 뒤집힌 결정의 근거는 [research.md](./research.md) D4·D7 참고.

**Tests**: Test-First(헌법 I). 모든 슬라이스가 실패 테스트 우선. Kotest `BehaviorSpec`(한국어 given/when/then).

---

## Phase 1: 정규화 · LLM 정제

- [X] `:core:kernel` `KoreanMenuNameNormalizer.matchKey` — NFC 후 `[가-힣]`만. 빈 키 = 메뉴 아님
- [X] `:core:kernel` `ScannedNameInterpreter` port + `InterpretedName(StandardName|NotFood)`
- [X] `:infra:llm` `ScannedNameParser` — 문자열 배열·`NOT_FOOD` 센티넬·코드펜스 제거·null 원소·**길이 검증**
- [X] `:infra:llm` `UpstageScannedNameInterpreter` — 단일 Upstage caller, 스캔당 동기 1콜
- [X] 영문 프롬프트 강화 — 기대 개수 명시·입력 번호 제시·중간 슬롯 `NOT_FOOD` few-shot·코드펜스 금지
- [X] `meogo.llm.*.temperature` 프로퍼티 추가, upstage `solar-pro` + `temperature=0`
- [X] `LlmConfiguration` `@ConditionalOnProperty(upstage.enabled)` 빈 조립, `:app:api` `runtimeOnly :infra:llm`

## Phase 2: food 완성 상태 · 매칭 키

- [X] `:core:food` `FoodContentStatus(INCOMPLETE|READY)`, `Food.contentStatus`, `isReady()`
- [X] `Food.incomplete(koreanName)` 팩토리 (description 플레이스홀더)
- [X] **`Food.overallRisk()`가 `!isReady()`면 `UNKNOWN` 강제** — "성분 비었으니 SAFE" 함정 도메인 차단
- [X] Flyway `food.content_status`(+인덱스, 기존 row READY 백필)
- [X] Flyway `food.korean_match_key` 생성 저장 컬럼(+인덱스) — **`COLLATE utf8mb4_bin` 필수**
- [X] `FoodJpaEntity` 읽기전용 매핑(`@Generated` 미사용 — `@SQLRestriction` 충돌)
- [X] kernel `matchKey` ↔ SQL 생성식 동등성 sync 테스트(Testcontainers)

## Phase 3: 매칭 · 미완성 등록 · serving gate

- [X] `FoodRepository.findByKoreanMatchKeys(keys)` — 스캔당 1쿼리(fetch join), 미완성 포함, 동음이의 최소 id + 경고 로깅
- [X] `FoodRepository.createIncomplete(koreanNames)` — 스캔당 1회·문장 2개(다중행 upsert + `IN` fetch join)
- [X] serving gate — 목록은 JPQL `contentStatus='READY'`, 상세는 어댑터 `takeIf { isReady() }`
- [X] **스코어링 배치 공유 쿼리(`findByIdIn…`)엔 gate 미적용** — 배치가 미완성 음식을 봐야 채운다

## Phase 4: 위험도 산출 (mock 제거)

- [X] 유스케이스가 `Food.overallRisk(avoidedCodes)` 직접 호출 — 카탈로그 미조회(KB-62 규칙과 동일)
- [X] `MockCyclingRiskAssessor` 삭제 — 응답 `riskLevel`이 실제 산출값

## Phase 5: 오케스트레이션 · 폴백

- [X] `MenuScanUseCase` — 정규화 게이트 → 전 항목 LLM 1콜 → 배치 매칭 → 미완성 등록 → 위험도
- [X] 한 스캔 안에서 같은 표준명은 미완성 음식 1회만 생성(이름 집합으로 dedup)
- [X] 폴백 — interpreter 미구성·예외·타임아웃·**응답 개수 불일치** 시 정규화 exact 매치
- [X] **폴백은 미완성 음식을 만들지 않는다**(food 테이블 오염 방지)
- [X] 응답 `degraded` 플래그 — 폴백 여부. "해석 대상 없음"은 강등 아님

## Phase 6: 스캔 기록 · 바운딩박스 제거

- [X] 요청에서 `boundingBox` 제거, `itemId` 중복 400 검증을 요청 DTO(`@AssertTrue`)로 이관
- [X] `MenuScan`·`ScannedMenuItem`·`ScanStatus`·`BoundingBox`·`MenuItemAssessment`·`MenuScanRepository` 삭제
- [X] `:infra:persistence` scan 패키지 전체 삭제
- [X] Flyway `menu_scan`·`scanned_menu_item` DROP (create는 develop 적용본이라 파일 삭제 금지)
- [X] 응답에서 `scanId`·`results[].id`·`reason` 제거

## Phase 7: 응답 계약 확정

- [X] 항목 매칭 결과 전용 타입 없음 — `matched`·`foodId` 를 확정된 `Food` 에서 파생
- [X] `matchStatus` 2상태(MATCHED/UNMATCHED), 비음식 항목은 결과에서 제외
- [X] Swagger(`MenuScanApi`) 재작성 — 흐름·상태·개수 비대칭 명시

## Phase 12: 복잡도 정리 (ponytail)

- [X] `MenuItemMatch` 제거 — `Food` 에서 전부 파생. `:core:scan` 은 deferred placeholder 로
- [X] `:core:scan` 죽은 의존 제거(`application:client`·`infra:persistence`)
- [X] LLM 어댑터 정리 — 중복 빈 목록 가드·조건 분리 함수·caller 목록 탐색 제거

## Phase 8: 검증

- [X] `./gradlew build` 전체 green (ArchUnit 포함)
- [X] SC-001 회귀 테스트 — 실측 6종 MATCHED, `메뉴판` UNMATCHED, 비한글 잡음 제외
- [X] 로컬 docker MySQL Flyway 전량 적용 검증(create→drop 순서, collation)
- [X] **실제 Upstage solar-pro 스모크** — 오탈자 교정·비음식 제외·미완성 등록·serving gate·재스캔 dedup

---

## Phase 9: develop 머지 (KB-62 검색 · KB-103 회원)

- [X] `Menu*` → `Food*` 리네임 수용(`findFoodPage`·`BrowseFoodsUseCase`·`FoodSummaryView`)
- [X] 위험도 규칙을 KB-62 쪽으로 통일 — `FoodRiskEvaluator` 삭제, 카탈로그 교집합 폐기
- [X] **검색 네이티브 쿼리에 serving gate 추가** — 스캔이 만든 미완성 음식이 검색에 노출되던 결함
- [X] Flyway 전량(회원 테이블 포함) 로컬 MySQL 적용 검증

## Phase 10: 동시성·오염 결함 수정

- [X] `FoodJpaEntity` 에 `uq_food_korean_name` 선언 — 테스트 스키마가 프로덕션과 달라 결함을 못 잡던 문제
- [X] `createIncomplete` upsert 전환 — 경합 데드락(500)·유령 행 배치 롤백 제거. 회귀 테스트 2종
- [X] `findByKoreanNameIn` fetch join — `toDomain()` 이 유발하던 N+1(이름당 컬렉션 SELECT) 제거
- [X] 메뉴명 255자 길이 가드 — 파서가 `NotFood` 로 떨구고 도메인이 최후 방어
- [X] 상태 컬럼 8종 `VARCHAR` → `ENUM` — 오타 유입 차단. 엔티티 `columnDefinition` 으로 스키마 일치

## Phase 11: 응답 계약 정리

- [X] 항목 식별자 `itemId` → `idx`
- [X] `matchStatus`(문자열) → `matched`(불리언). `foodId` 유무와 구분됨을 문서화
- [X] `lang` 쿼리 파라미터 제거 — 한국어 고정, 회원 언어 설정 연동은 TODO
- [X] `MenuScanUseCase` 이름 정리 — `confirmedByInterpreter` 등으로 폴백 규칙을 이름에 노출

## 후속 (이 작업 범위 밖)

- 회원 기능 도입 시 `MockAvoidedSubstanceProvider` → 실제 `MemberProfile` 회피 성분
- 조사 배치: `food WHERE content_status=INCOMPLETE` 소비 → 레시피·설명·번역 채우고 `READY` 전이
