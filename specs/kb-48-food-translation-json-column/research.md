# Phase 0 Research: 음식 번역결과 JSON 칼럼 통합 (KB-48)

기존 코드에 참조 선례(`avoidance_substance.translations`, #25)가 있어 미결 NEEDS CLARIFICATION 은 없다. 아래는 설계 결정과 근거다.

## D1. 저장 형태 — 음식 행에 두 개의 JSON 칼럼

- **Decision**: `food` 테이블에 `name_translations JSON NOT NULL`, `description_translations JSON NOT NULL` 두 칼럼을 추가한다. 각 칼럼은 `언어코드(LanguageCode.code) → 번역 문자열` 객체이며 **`ko` 키는 담지 않는다**(원문은 `food.korean_name`·`food.description`).
- **Rationale**: 기피성분(`avoidance_substance.translations`)과 완전 동형 → 매핑·복원·폴백 코드를 그대로 답습. 번역이 음식 행에 함께 있어 상세조회 시 애그리거트 로드 한 번으로 번역까지 확보(언어별 추가 조회 0회, SC-004).
- **Alternatives rejected**:
  - 단일 JSON 칼럼에 `{name:{...}, description:{...}}` 중첩 — 기피성분 선례(성분당 맵 1개)와 형태가 달라지고, 두 필드의 검증·복원 코드가 한 칼럼에 얽힘. 필드별 칼럼이 더 단순.
  - 번역 테이블 유지 — 현행. 언어당 별도 SELECT·중복 저장을 없애려는 목적에 반함.

## D2. 번역 맵의 소유 위치 — 도메인 `FoodContent`

- **Decision**: 번역 맵을 `FoodContent`(koreanName·description 보유)에 `nameTranslations`·`descriptionTranslations: Map<LanguageCode, String>` 로 넣고, 폴백 해석 메서드 `name(lang)`·`description(lang)` 를 둔다(`lang==KO` → 원문, else `map[lang] ?: 원문`). `AvoidanceSubstance.displayName(lang)`과 동일한 규칙.
- **Rationale**: 번역은 음식이 소유한 콘텐츠 — 애그리거트 안에 두는 것이 기피성분 선례·헌법 II(값 응집)와 일치. 폴백 로직이 유스케이스에서 도메인으로 내려가 재사용·테스트가 쉬워짐.
- **Alternatives rejected**: 유스케이스가 맵을 직접 뒤져 폴백 — 현행 `resolve*` 를 그대로 옮기는 형태지만 도메인 규칙이 application 에 누수. 선례(displayName)와 어긋남.

## D3. 도메인 ORM-free 유지 — 매핑은 엔티티에서 `LanguageCode` ↔ `String` 키 변환

- **Decision**: `FoodJpaEntity` 가 `@JdbcTypeCode(SqlTypes.JSON) var nameTranslations: Map<String,String>` 로 원시 문자열 키 맵을 보관하고, `toDomain()` 에서 `LanguageCode.entries` 와 매칭해 `Map<LanguageCode,String>` 로 복원한다(미지의 키는 무시). 도메인은 `LanguageCode` 키 맵만 안다.
- **Rationale**: `AvoidanceSubstanceJpaEntity.resolveTranslations()` 와 동일 패턴. Hibernate 6 네이티브 JSON 매핑이라 별도 `AttributeConverter` 불필요(repo 에 커스텀 컨버터 부재 — 선례 답습).
- **Alternatives rejected**: 도메인에 `Map<String,String>` 노출 — 타입 안전성 하락, 선례와 불일치.

## D4. 데이터 이행 — `JSON_OBJECTAGG` 로 행 집계 백필 후 테이블 DROP

- **Decision**: 신규 `V10__jsonify_food_translations.sql`:
  1. `food` 에 두 JSON 칼럼 NULL 로 추가 → `JSON_OBJECT()` 로 전 행 초기화.
  2. `food_name_translation`(ACTIVE, `name <> ''`)에서 `SELECT food_id, JSON_OBJECTAGG(lang_code, name) ... GROUP BY food_id` 를 `food` 에 조인 UPDATE. 설명도 동일하게 `food_description_translation`(ACTIVE, `content <> ''`) → `description_translations`. **빈 문자열 값은 집계에서 제외**해 해당 언어 키를 만들지 않는다(FR-003 — 폴백 대상 유지, V6 의 `<> ''` 가드 답습).
  3. 두 칼럼 `MODIFY ... JSON NOT NULL`(전 행이 최소 빈 객체라 non-null 보장).
  4. `DROP TABLE food_name_translation, food_description_translation`.
- **Rationale**: #25(V6)의 "백필 → NOT NULL → DROP" 절차를 답습하되, V6 는 컬럼→JSON 이었고 여기선 **행→JSON** 이라 집계 함수 `JSON_OBJECTAGG`(MySQL 8) 사용. 소프트삭제 정합: 앱은 `@SQLRestriction("status='ACTIVE'")` 로 ACTIVE 만 읽으므로 백필도 **`WHERE status='ACTIVE'`** 로 한정해 관찰 동작을 보존.
- **DROP 안전성**: 두 번역 테이블은 `food(id)` 를 참조하는 **자식**(inbound FK 없음) → DROP 시 FK 순서 문제 없음(V8 의 uq_fdt 백킹 인덱스 이슈 같은 것 없음).
- **Alternatives rejected**:
  - 2단계 이행(테이블 잠시 유지) — 소비 코드가 즉시 JSON 으로 전환되므로 테이블을 남길 이유 없음. #25 선례도 같은 릴리스 DROP. (사용자가 원하면 조정 가능하나 기본은 단일 릴리스.)
  - 앱 코드로 백필 — 마이그레이션 단일 출처 원칙·재현성에 반함.

## D5. 응답 계약 동결 — web 계층 무변경

- **Decision**: `FoodDetailResponse`(필드 `name`·`imageRef`·`description`·`spiciness`·`ingredients[]{name,iconRef,inclusionPercent,riskStatus}`)와 컨트롤러/`FoodDetailApi` 를 **건드리지 않는다**. `GetFoodDetailResult` 도 형태 불변.
- **Rationale**: 저장 원천 교체가 목적. 유스케이스가 반환하는 `name`·`description` 값이 이전과 동일하면 응답도 동일(FR-005, SC-001). 계약 검증은 기존 web 테스트가 회귀로 커버.
- **Alternatives rejected**: 없음(계약 동결이 요구사항).

## D6. 번역 부재/미지원 언어 동작 — 현행 유지

- **Decision**: (1) 미지정(null·빈·공백)→`ko`, (2) 지원 언어 번역 부재→`ko` 폴백(맵 미스), (3) 미지원 코드→`LanguageCode.from` 이 `UNSUPPORTED_LANGUAGE` 예외(HTTP 400). JSON 맵에 우연히 든 미지의 키는 `toDomain` 복원 시 무시.
- **Rationale**: 헌법 V·#23 정책을 그대로 승계. 저장 형태만 바뀔 뿐 언어 결정 경로(`LanguageResolver`/`LanguageCode.from`)는 불변.

## 미결 사항

없음. 모든 결정이 기존 선례(#25·#23)와 정합하며 [NEEDS CLARIFICATION] 잔여 없음.
