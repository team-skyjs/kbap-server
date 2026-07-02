# Phase 0 Research: 회피·주의 성분 어그리게이트 분리

리팩터라 미지의 기술 선택은 없다. 설계 결정(어떻게 분리·복원·검증하는가)만 확정한다.

## D-1. enum 축소 방식 — 식별자 전용 `AvoidanceSubstanceCode`

- **Decision**: `AvoidanceSubstance`(data 보유 enum)를 `AvoidanceSubstanceCode`(코드 상수 81개, 필드·init 없음)로 리네임한다. koName·categories 는 제거한다. 타 컨텍스트의 망라 매칭·타입 안전 참조(#16)는 이 enum 으로 계속 가능.
- **Rationale**: 식별자와 데이터를 분리(spec FR-006). enum 은 컴파일 타임 코드 집합·`when` 망라성만 담당. 데이터 중복 원천 제거.
- **Alternatives**: enum 유지 + DB 병행(현행) → redundancy·데이터 누수 지속(기각 사유이자 이 기능의 목적). sealed class → enum 의 망라 매칭·`entries` 이점 상실(기각).

## D-2. 어그리게이트 복원 위치·형태

- **Decision**: `AvoidanceSubstance` 를 `@AggregateRoot` 도메인 클래스로 둔다 — `private constructor(id, code, koreanName, translations: Map<LanguageCode,String>, categories: Set<AvoidanceCategory>)` + `companion object { fun reconstitute(...) }`. 행위 `displayName(lang)`(KO→koreanName, else translations[lang] ?: koreanName)·`belongsTo(category)`. 불변(전부 `val`, public copy 미노출).
- **Rationale**: 도메인 규약(불변·JPA 미import·행위 보유). displayName 이 도메인에 있어 어댑터의 `translatedName` 이 소멸 → Finding ① 구조적 해결.
- **Alternatives**: 데이터 클래스 + 외부 서비스가 displayName 계산 → 행위 분산·불변 통제 약화(기각). `from(domain)` 쓰기 팩토리 → **읽기 전용 카탈로그라 쓰기 경로 없음**, `toDomain()` 만 둔다(불필요한 대칭 제거).

## D-3. JPA 분류 저장 — String 컬럼 + 경계 변환

- **Decision**: `AvoidanceSubstanceCategoryJpaEntity.category` 를 `@Enumerated`(도메인 enum) → `@Column var category: String` 으로 변경. 조회 시 `AvoidanceCategory.valueOf(str)` 로 변환, `byCategory` 는 `category = :name`(문자열) 으로 질의.
- **Rationale**: 코드베이스 관례(RiskLevel·FoodDescriptionKind: String 저장 + 경계 변환) 정렬. JPA 엔티티 ↔ 도메인 enum 결합 제거(spec FR-007). 스키마 불변(이미 VARCHAR).
- **Alternatives**: `@Enumerated(STRING)` 유지 → 값은 같지만 엔티티가 도메인 타입에 컴파일 결합(관례 위반, 기각).

## D-4. 어그리게이트 복원 쿼리 — 배치 in-절(N+1 회피)

- **Decision**: 어댑터가 공통 "substanceIds → List<AvoidanceSubstance>" 조립을 둔다: ① 성분행 `findByIdIn`(번역 컬럼) ② 분류행 `findBySubstanceIdIn`(멤버십 전체) 을 각각 **한 번** 조회해 메모리에서 group → `reconstitute`. `byCategory`·`findByCodes`·`findByIngredientIds` 가 이 조립을 재사용.
- **Rationale**: 조회 수가 성분 수와 무관한 상수 단계(spec FR-008·SC-003). 기존 어댑터의 in-절 스타일과 일관. 도메인 규약(EAGER 금지·fetch join 명시)과 충돌 없음(별도 배치 조회라 매핑 자체가 필요 없음).
- **Alternatives**: 엔티티에 `@OneToMany` category + fetch join → 가능하나 소프트삭제/`@SQLRestriction`·조인 폭 고려 필요, 현행 어댑터 스타일과 이질(차선). `@ElementCollection` → 별도 매핑 도입 오버(기각).

## D-5. 시드 원천·정합 불변식 축소

- **Decision**: 시드 데이터 단일 출처 = `specs/004-avoidance-catalog/seed/avoidance-substances.json`(V5 가 DB 로 적재). `AvoidanceCatalogSeedSyncTest` 를 **코드 집합 일치**(V5 SQL 코드 == `AvoidanceSubstanceCode.entries`)로 축소한다 — koName·번역·멤버십의 enum↔SQL 대조는 제거(enum 이 더는 그 데이터를 안 이므로 비교 대상 소멸).
- **Rationale**: enum 데이터 제거의 직접 귀결(spec FR-009). 데이터 정합은 이제 JSON↔DB(seed) 문제이지 enum↔DB 문제가 아니다.
- **Alternatives**: JSON↔V5 SQL 전체 대조 테스트 신설 → 유용하나 이 리팩터 범위 밖(코드 집합 일치로 최소 회귀 확보, 확장은 후속). enum↔SQL 대조 유지 → 제거할 데이터에 의존(불가).

## D-6. 전이 유물 제거 — `AvoidanceCatalog`·`AvoidanceSubstanceTranslations`

- **Decision**: 두 도메인 객체를 삭제한다(및 관련 테스트 `AvoidanceCatalogTest`). displayName/byCategory 는 어그리게이트·port 로 대체. 프로덕션 사용처는 테스트뿐임을 grep 으로 확인(안전 제거).
- **Rationale**: 004 시절 enum+in-memory 카탈로그 유물. 006 이후 DB 가 단일 출처라 displayName 을 enum 데이터로 계산하던 경로가 redundancy·누수 원천. 제거로 단일화.
- **Alternatives**: `AvoidanceCatalog` 를 repository 위임 파사드로 유지 → 불필요한 간접(기각). `AvoidanceSubstanceTranslations` 를 JSON 로더로 전환 → 시드 원천은 이미 JSON→DB, 런타임 재적재 불요(기각).

## D-7. 회귀 가드 — ArchUnit·동작 보존

- **Decision**: `ModuleBoundaryTest` 에 회귀 검사 보강 — (a) `AvoidanceSubstanceCode` 가 선언 필드 0(리플렉션), (b) `:infra:persistence` 의 avoidance 엔티티가 도메인 enum 을 `@Enumerated` 필드로 쓰지 않음(String 저장 확인). 기존 어댑터·resolver 동작 테스트는 어그리게이트 반환으로 갱신하되 **관측 동작(성분 집합·분류 소속·표시명·매핑 결과)** 을 보존.
- **Rationale**: spec 의 "Finding ①·JPA 결합·redundancy 해소 확인 + ArchUnit 회귀" 이행. 구조적 재발 방지.
- **Alternatives**: 리뷰만으로 확인 → 회귀 자동화 없음(기각).

## 열린 항목(후속, 이 기능 범위 밖)

- **원칙 V 문구 조정**: enum 무데이터 전제로 예외 단서 재서술 → `/speckit-constitution`(MINOR bump). plan Constitution Check 에 명시.
- **#16 판정 로직**: 어그리게이트/코드 enum 을 실제 소비. 이 리팩터를 선행으로 두면 안전.
