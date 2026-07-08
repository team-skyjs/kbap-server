# Quickstart: 메뉴 스캔 메뉴명 정제 검증

## 전제

- 로컬 docker MySQL 8.4 (`meogo-mysql`, root/root) 또는 통합 테스트는 MySQL Testcontainers.
- foods 테이블에 매칭 대상 시드(예: `김치찌개`)가 존재.
- 정상 경로 검증엔 `meogo.llm.upstage.*` 구성(또는 fake interpreter). 미구성 시 폴백 경로(US2)로 동작.

## US1 — 정상 경로 (정규화 → 전부 LLM → 매치 → 대기열)

1. `./gradlew :core:kernel:test` — `KoreanMenuNameNormalizer`(혼합 로마자·기호·공백·비한글 빈 키·띄어쓰기변형) + `InterpretedName` 순수 단위.
2. `./gradlew :infra:llm:test` — `ScannedNameParser` 배열 응답 파싱(정상·NOT_FOOD·부분 실패), `UpstageScannedNameInterpreter` 는 fake `LlmModelCaller` 로 단위(배열 1콜·입력순서 1:1).
3. `./gradlew :infra:persistence:test` — `findByKoreanMatchKey`(hit/miss/동음이의) + **normalizer↔SQL 규칙 동등성 sync 테스트** + `PendingMenuRepository` enqueue dedup (Testcontainers).
4. `./gradlew :app:api:test` — MockMvc(fake interpreter): `"김치찌개 kimchi jjigae"`·`"김치찌게"` → `MATCHED`+같은 `foodId`. `"우주라면"` → `PENDING` + `pending_menus` 1행. `"원산지 중국"`·`"MacBook Air F9"` → `NOT_FOOD`(빈 키는 LLM 스킵), 대기열 미등록.
5. 같은 미등록 표준명 2회 스캔 → `pending_menus` 여전히 1행(unique dedup, SC-005).

## US2 — 폴백 (LLM 장애·미구성)

6. 예외/타임아웃/부재(null) fake interpreter 주입 → 아는 메뉴 `"김치찌개"` 는 정규화 exact 매치로 `MATCHED`, 잡음·미등록은 원문 `PENDING`+대기열. 스캔 응답 200 성공(FR-006).
7. `meogo.llm.upstage.*` 미구성 부팅 → web 정상 기동, 폴백 규칙으로 응답(interpreter 빈 부재 안전).

## 실측 회귀 (SC-001)

8. 실측 로그의 6종(김치찌개·된장찌개·순두부찌개·부대찌개·고추장찌개·닭볶음탕) + "메뉴판"·잡음 혼합 입력 → 6종 매칭(또는 PENDING), "메뉴판"·잡음은 MATCHED 되지 않음.

## Flyway 로컬 확인

마이그레이션은 테스트에서 실행되지 않음(Testcontainers 스키마는 JPA ddl). 새 V 스크립트(foods 생성컬럼·scan 항목 컬럼·pending_menus)는 로컬 docker MySQL 에 DROP+CREATE 후 부팅해 검증([[flyway-migration-validation-gap]]). 생성 컬럼 `REGEXP_REPLACE` 는 MySQL 8 전용 — H2 미고려(CLAUDE.md).
