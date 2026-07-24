# Phase 0 Research: 기피 성분 스키마 리팩터

**Date**: 2026-07-03 | **Feature**: 009-avoidance-schema-refactor

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 확정을 위한 결정 기록이다.

---

## R1. MySQL JSON 컬럼의 Hibernate 매핑 방식

**Decision**: `AvoidanceSubstanceJpaEntity` 에 `@JdbcTypeCode(SqlTypes.JSON)` 을 붙인 `Map<String, String>` 프로퍼티(`translations`)를 두고, MySQL 네이티브 `JSON` 컬럼에 매핑한다. Hibernate 가 jackson 으로 Map↔JSON 직렬화를 수행한다. 엔티티 프로퍼티는 `Map<String,String>`(키=`LanguageCode.code`)이며, `toDomain()` 에서 `LanguageCode` 로 변환해 도메인의 `Map<LanguageCode,String>` 를 만든다.

**Rationale**:
- Boot 4.1 = Hibernate 7. `@JdbcTypeCode(SqlTypes.JSON)` 는 표준 방식이고 별도 라이브러리(hypersistence-utils 등) 불필요.
- jackson-module-kotlin 이 `meogo.spring-conventions` 로 전 Spring 모듈에 이미 제공 → 직렬화 인프라 존재.
- 도메인은 ORM-free 유지(엔티티만 JSON 을 알고, 도메인은 순수 `Map<LanguageCode,String>`). 원칙 IV 준수.

**Alternatives considered**:
- **JPA `AttributeConverter<Map, String>` + VARCHAR/TEXT**: DB 종류에 무관하지만 MySQL 에서 네이티브 `JSON` 타입이 아니라 사용자 요구("json 타입 칼럼")에 미달. 기각.
- **@ElementCollection(별도 번역 테이블)**: 다시 정규화 테이블로 회귀 — "단일 컬럼 통합" 목표와 배치. 기각.
- **hypersistence-utils `@Type(JsonType)`**: 외부 의존 추가 필요. Hibernate 7 기본 기능으로 충분해 기각.

**검증 리스크 & 대응**: 테스트는 H2(`ddl-auto=create-drop`, Flyway off)에서 엔티티 매핑으로 스키마를 생성한다. H2 2.x 는 `JSON` 타입을 지원하고 Hibernate H2Dialect 가 `SqlTypes.JSON` 을 매핑한다. **원칙 I(Test-First)에 따라 `AvoidanceSubstanceRepositoryAdapterTest`(H2)에서 JSON 왕복(저장→조회→displayName)을 먼저 Red 로 세워 실제 동작을 확인한다.** 만약 H2 에서 매핑이 깨지면(가능성 낮음) 대응 순서: (a) 엔티티에 `columnDefinition` 미지정으로 두어 방언별 기본 JSON DDL 사용, (b) 그래도 불가 시 최후로 AttributeConverter+TEXT 로 폴백하되 MySQL V6 는 `JSON` 유지(엔티티는 String 매핑). — 이 폴백은 R1 기본안이 통과하면 불필요.

---

## R2. JSON 키 포맷 (언어 식별자)

**Decision**: JSON 객체의 키는 `LanguageCode.code` 문자열을 사용한다 — 예: `{"en":"Egg","ja":"卵","zh-Hans":"鸡蛋"}`. `ko` 는 JSON 에 넣지 않는다(전용 `korean_name` 컬럼).

**Rationale**:
- 기존 컬럼명(`name_zh_hans`)·`LanguageCode.code`("zh-Hans")와 의미가 일치해 사람이 읽기 쉽고, 향후 API/로그와도 자연스럽다.
- enum 이름(`ZH_HANS`)보다 표준 BCP-47 스타일 코드가 데이터로서 자기설명적.
- `LanguageCode` 는 이미 `code` 값을 보유(`KO("ko")`, `EN("en")`, `ZH_HANS("zh-Hans")` …) → `code`↔enum 변환 헬퍼만 있으면 됨(없으면 추가).

**Alternatives considered**:
- **enum name 키(`EN`,`ZH_HANS`)**: 코드에서 `valueOf` 간단하지만 DB 데이터 가독성↓, 컬럼명 규칙과 불일치. 기각.

**Assumption**: `LanguageCode` 는 `code`("en" 등)로 enum 을 역조회하는 수단을 제공하거나 이번에 추가한다(기존 언어 해석 로직 재사용 가능하면 그것을 쓴다).

---

## R3. 마이그레이션 전략 (이미 배포된 DB)

**Decision**: V5 는 **불변**으로 두고 새 `V6__drop_avoidance_category_and_jsonify_translations.sql` 를 추가한다. V6 순서:

1. `ALTER TABLE avoidance_substance ADD COLUMN translations JSON NULL;`
2. 백필 — 기존 `name_*` 값에서 비-NULL 언어만 JSON 객체로 구성(`JSON_OBJECT` + NULL 키 제거, 또는 각 언어 조건부 병합). 번역이 하나도 없으면 `{}`. 키는 R2 의 code 값(`en`,`ja`,`zh-Hans`,`zh-Hant`,`vi`,`id`,`th`,`ru`,`es`).
3. `ALTER TABLE avoidance_substance DROP COLUMN name_zh_hans, ..., name_es;` (9종 제거)
4. `DROP TABLE avoidance_substance_category;` (인입 FK 없음 — `ingredient_avoidance_substance` 는 `avoidance_substance(id)` 만 참조하므로 안전)

**Rationale**: 운영 DB 에 이미 적용된 마이그레이션은 수정하지 않는다(Flyway 정석 — 체크섬 불일치·재적용 방지). Forward-only 로 데이터 보존 이전.

**Alternatives considered**:
- **V5 재작성**: 이미 배포되어 체크섬 깨짐·재현 불가. 기각(사용자 확인: "이미 배포됨").
- **번역을 애플리케이션 코드로 백필**: 마이그레이션 원자성↓, 배치 필요. SQL 백필이 단순·결정적이라 기각.

**Note**: 엔티티 매핑(H2 테스트 스키마)과 V6(MySQL prod 스키마)는 서로 다른 스키마 소스지만 동일 컬럼 형태(`translations JSON`, `name_*` 부재, category 테이블 부재)를 만들어야 한다 — 기존 프로젝트의 이원 스키마(Flyway=prod, Hibernate ddl=test) 관행과 동일.

---

## R4. 분류 카테고리 제거 파급 & Reconstitutor 동작 변화

**Decision**: 카테고리를 전 계층에서 제거하며, 특히 `AvoidanceSubstanceReconstitutor` 의 **"카테고리가 없으면 성분을 drop"** 하던 로직을 제거해 모든 성분이 무조건 복원되게 한다.

**핵심 발견(회귀 위험)**: 현재 `Reconstitutor.fromRows` 는
`val categories = categoriesBySubstanceId[row.id]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null`
로, **카테고리 조인 결과가 없는 성분을 조용히 누락**시킨다. 카테고리 테이블/조인을 제거하면 이 가드도 반드시 제거해야 한다 — 아니면 전 성분이 사라진다. 현재 81종은 모두 카테고리가 시딩돼 있어(그래서 지금은 전부 복원됨) 제거 후에도 "전 성분 복원" 동작은 동일하게 유지된다(오히려 카테고리 시딩 누락에 취약하던 점이 사라짐).

**제거 대상(파급 전체)**:
- 도메인: `AvoidanceCategory`(삭제), `AvoidanceSubstance.categories`/`belongsTo`/`require(categories 1..3)`, `AvoidanceSubstanceRepository.byCategory`.
- 영속: `AvoidanceSubstanceCategoryJpaEntity`/`...JpaRepository`(삭제), `Reconstitutor`(category 조인·drop 필터 제거 → `byIds`/`fromRows` 가 rows 만으로 복원), `RepositoryAdapter`(`byCategory` 구현·category 리포지토리 생성자 의존 제거).
- 아키텍처 테스트: `ModuleBoundaryTest` 의 "영속 avoidance 엔티티의 분류 저장 형식 회귀" given 블록 제거. (단, `AvoidanceSubstanceCode` 가 `label` 만 갖는다는 규칙 검증 given 은 **유지** — 원칙 V.)
- 테스트: `AvoidanceSubstanceTest`(categories 픽스처·`AvoidanceCategory.entries` 검증 제거), `AvoidanceSubstanceRepositoryAdapterTest`(saveMembership/byCategory 케이스 제거, JSON 왕복·findByCodes 로 대체), `FoodAvoidanceSubstanceResolverTest`(categories 픽스처 제거).

**Rationale**: `byCategory`/`belongsTo`/`categories` 를 호출하는 **프로덕션 소비자가 전무**(grep 확인 — port 정의·테스트 픽스처만 참조). 프로덕션 조회 경로는 `findByCodes`(코드로 조회)와 `IngredientAvoidanceSubstanceRepository.findByIngredientIds`(재료→성분) 두 개이며 둘 다 카테고리 불사용. 따라서 제거는 관찰 동작·API 무변경(SC-003/SC-004).

**Alternatives considered**: 개념 유지·컬럼만 정리 — 사용자 결정("완전 제거")과 배치, 죽은 개념 유지 비용만 발생. 기각.

---

## 미해결 항목

없음. 모든 결정이 확정되어 Phase 1(data-model)·이후 `/speckit-tasks` 로 진행 가능.
