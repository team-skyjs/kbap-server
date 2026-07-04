# Implementation Plan: 음식 번역결과 JSON 칼럼 통합 (KB-48)

**Branch**: `kb-48-food-translation-json-column` | **Date**: 2026-07-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-48-food-translation-json-column/spec.md`

## Summary

음식(Food)의 음식명·설명 번역을 언어당 한 행씩 별도 테이블(`food_name_translation`·`food_description_translation`)에 저장하고 상세조회 때 언어별로 추가 SELECT 하던 구조를, 기피성분(`avoidance_substance.translations`, #25)과 **동형**으로 바꾼다 — **음식 행에 `name_translations`·`description_translations` 두 JSON 칼럼**을 두고 `언어코드 → 문자열` 맵을 담아 **음식 애그리거트와 함께 한 번에 로드**한다. 번역 해석(요청 언어 값 or ko 폴백)은 도메인(`FoodContent`)이 `AvoidanceSubstance.displayName(lang)`과 같은 방식으로 수행한다.

**핵심 제약(사용자 지시 + 헌법 V)**: 상세조회 API(`GET /api/v1/foods/detail`)의 **응답 계약과 언어 폴백/에러 동작을 동결**한다. 저장 방식만 교체하고 외부에서 관찰되는 값은 불변. 기존 두 번역 테이블 데이터는 **무손실 이행** 후 테이블을 DROP 해 단일 출처화한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), Hibernate 6 JSON 매핑(`@JdbcTypeCode(SqlTypes.JSON)`), Flyway(+flyway-mysql), springdoc-openapi

**Storage**: MySQL(prod) / H2(test, create-drop, Flyway off) — 스키마 owner=`:app:api`. MySQL `JSON` 컬럼 + `JSON_OBJECTAGG` 백필

**Testing**: JUnit5 platform + Kotest `BehaviorSpec`(given/when/then 한국어), `kotest-extensions-spring`(MockMvc·H2 통합)

**Target Platform**: Linux server (web bootJar `:app:api`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스, ADR-0008)

**Performance Goals**: 상세조회 1회의 번역 해석에 **언어별 추가 조회 0회**(번역이 음식 행 JSON 으로 함께 로드). 기존 대비 비-ko 요청당 최대 2회 SELECT 절감.

**Constraints**: 도메인 ORM-free·완전 Spring-free 유지; food 는 avoidance 를 코드/값으로만 참조; 외부 API JSON 계약 무변경; 번역 값 ≤255자.

**Scale/Scope**: food 도메인·영속·음식상세 유스케이스/컨트롤러 한정. 기피성분 카탈로그·회피 프로필·메뉴 스캔 불변. 음식 시드 10종 × ≤9개 언어.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 준수 방법 | 판정 |
|------|-----------|------|
| **I. Test-First** | 도메인(`FoodContent` 번역 해석)·영속(H2 JSON 라운드트립·폴백)·유스케이스(계약 불변)·컨트롤러(계약 동결) 각 층 실패 테스트 선작성 → Green → Refactor. tasks 에서 층별 테스트를 구현 앞에 배치. | ✅ |
| **II. Bounded Contexts** | `:core:food` 는 `:core:avoidance` 미의존 유지. 번역 맵은 **food 소유 콘텐츠**로 `FoodContent` 안에 둔다. 언어 키 타입은 kernel 공용 `LanguageCode`(food·avoidance 공유 vocabulary). | ✅ |
| **III. Layered Dependency** | 의존 방향 불변(app→application→core→kernel). JSON 매핑·컨버전은 `:infra:persistence` 엔티티에만. 유스케이스는 port(`FoodRepository`)로만 접근. | ✅ |
| **IV. Persistence Encapsulation** | 모든 JPA(신규 JSON 칼럼 매핑 포함)는 `:infra:persistence`. 도메인 ORM-free(맵은 순수 `Map<LanguageCode,String>`). 삭제되는 번역 엔티티/리포지토리도 이 모듈 안. | ✅ |
| **V. Language Policy** | ko 원문은 번역 저장소 밖(`food.korean_name`·`food.description`)에 유지. 9개 대상 언어 키만 JSON 에 저장. (1)미지정→ko 기본, (2)지원 언어 번역 부재→ko 폴백, (3)미지원 코드→400(`LanguageCode.from`) — 세 경우 모두 현행대로. 동적 메뉴 콘텐츠라 "식별자 enum + DB 단일출처" 예외 대상 아님(콘텐츠는 DB). | ✅ |
| **추가: 도메인/영속 모델 직접 노출 금지** | 응답은 `FoodDetailResponse` DTO 로 감싸고 도메인/엔티티 직접 반환 안 함(현행 유지). | ✅ |

**결과**: 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-48-food-translation-json-column/
├── plan.md              # (this file)
├── research.md          # Phase 0 — 설계 결정(JSON 매핑·백필 방식·계약 동결·폴백 이관·삭제 순서)
├── data-model.md        # Phase 1 — 도메인/엔티티/스키마 변경
├── quickstart.md        # Phase 1 — 검증 시나리오(계약 동일성·무손실 이행·쿼리 절감)
├── contracts/
│   └── food-detail-api.md   # 동결된 GET /api/v1/foods/detail 계약(저장 원천만 교체)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출(이 명령이 만들지 않음)
```

### Source Code (repository root) — 영향 범위

```text
core/food/src/main/kotlin/com/meogo/core/food/
├── FoodContent.kt        # 변경: nameTranslations·descriptionTranslations(Map<LanguageCode,String>) 추가 + name(lang)/description(lang) 폴백 메서드
└── FoodRepository.kt     # 변경: findFoodNameTranslation·findFoodDescriptionTranslation 포트 제거(번역이 Food 에 포함)

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
├── FoodJpaEntity.kt                          # 변경: name_translations·description_translations JSON 칼럼 매핑, toDomain 에서 FoodContent 에 맵 주입
├── FoodRepositoryAdapter.kt                  # 변경: 번역 리포지토리 2종 의존·메서드 제거(findByKoreanName 만)
├── FoodNameTranslationJpaEntity.kt           # 삭제
├── FoodNameTranslationJpaRepository.kt        # 삭제
├── FoodDescriptionTranslationJpaEntity.kt    # 삭제
└── FoodDescriptionTranslationJpaRepository.kt # 삭제

application/client/src/main/kotlin/com/meogo/application/client/food/usecase/
└── GetFoodDetailUseCase.kt   # 변경: resolveFoodName/resolveDescription → food.content.name(lang)/description(lang) 도메인 폴백 사용, 번역 포트 호출 제거

app/api/src/main/kotlin/com/meogo/app/api/food/
├── FoodDetailResponse.kt     # 무변경(계약 동결). @Schema 문구도 유지
└── FoodDetailController.kt / FoodDetailApi.kt   # 무변경

app/api/src/main/resources/db/migration/
└── V10__jsonify_food_translations.sql   # 신규: food 에 두 JSON 칼럼 추가 → 두 번역 테이블에서 JSON_OBJECTAGG 백필 → NOT NULL → 두 테이블 DROP
```

**Structure Decision**: 기존 모듈러 모놀리스 레이아웃을 그대로 사용한다. 신규 상태는 소유 계층 규칙에 따라 배치(번역 맵→`:core:food`의 `FoodContent`, JSON 매핑→`:infra:persistence`, 마이그레이션→`:app:api`). 새 모듈·새 파일 최소화(도메인/영속 파일 4종 삭제, 마이그레이션 1종 신설).

## Complexity Tracking

> 해당 없음 — Constitution Check 위반 없음.
