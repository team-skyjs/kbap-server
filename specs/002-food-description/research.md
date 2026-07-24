# Research: 음식 설명(간단·자세) 추가

**Feature**: 002-food-description | **Date**: 2026-06-29

Phase 0 — spec clarify 로 NEEDS CLARIFICATION 은 모두 해소됨. 본 문서는 plan 단계에서 내린 설계 결정의 근거를 남긴다.

## R1. 번역 저장 구조 — 단일 테이블 + kind 판별

**Decision**: 설명 번역을 **단일 `food_description_translation(food_id, kind, lang_code, content)`** 테이블에 두고 `kind ∈ {BRIEF, DETAILED}` 컬럼으로 종류를 구분한다. `UNIQUE(food_id, kind, lang_code)`.

**Rationale**:
- 기존 `food_name_translation`·`ingredient_name_translation`이 따르는 **"한 행 = 한 번역 문자열"** 모델을 유지한다(조회 결과를 kind 로 매핑, 필드별 독립 폴백이 자연스럽다).
- 설명 종류가 늘어도(예: 향후 'tip', 'origin') 테이블 추가 없이 `kind` 값만 확장된다.
- 종류별 별도 테이블(`food_brief_description_translation`·`food_detailed_description_translation`) 대비 테이블·리포지토리·adapter 코드 중복을 줄인다.

**Alternatives considered**:
- **종류별 2개 테이블**: 이름 번역(개념별 1테이블) 선례에 더 가깝지만, 간단·자세는 "음식 설명"이라는 **한 개념의 2종**이라 판별 컬럼이 더 자연스럽다. 테이블·코드 2배.
- **(food_id, lang_code) 한 행에 brief·content 두 컬럼**: 행 수는 줄지만 한 행에 두 번역이 묶여 **필드별 독립 부재(부분 번역)** 표현이 어색하고(컬럼별 nullable 혼재), ko 미저장 정책과 정렬이 깨진다.
- **기존 `food_name_translation` 재사용/확장**: 이름과 설명은 길이·의미가 달라(이름 255 vs 설명 1024) 한 테이블에 섞으면 컬럼 의미가 흐려진다. 분리 유지.

## R2. NOT NULL 컬럼을 기존 테이블에 추가 — 3단계 마이그레이션

**Decision**: V4 에서 `food.brief_description`·`food.detailed_description` 를 **① nullable 로 ADD → ② 기존 seed 행 UPDATE(ko 텍스트 채움) + 번역 INSERT → ③ `MODIFY ... NOT NULL` 로 제약 강화** 순서로 적용한다.

**Rationale**:
- V3 가 이미 음식 행을 INSERT 해 둔 상태라, 처음부터 `NOT NULL` 컬럼을 추가하면 기존 행에 값이 없어 실패하거나 빈 문자열 기본값이 박힌다.
- nullable ADD → 값 채움 → NOT NULL 강화는 **데이터 유실/기본값 오염 없이** 불변(non-null)을 보장하는 표준 절차다.
- Flyway 는 forward-only — V1~V3 는 수정하지 않고 V4 단일 파일에서 위 3단계를 순차 실행한다.

**Alternatives considered**:
- **`NOT NULL DEFAULT ''` 로 ADD**: 빈 문자열이 남아 도메인 불변(notBlank)·요구사항(항상 채움)과 충돌. 기각.
- **V3 수정**: forward-only·적용 완료 위반. 기각.

## R3. 도메인 port — 음식당 두 kind 를 1쿼리로

**Decision**: `FoodRepository.findFoodDescriptionTranslations(foodId, lang): Map<FoodDescriptionKind, String>` — 한 음식의 요청 lang 번역(BRIEF·DETAILED)을 **한 번에** 조회한다. `lang == ko` 면 조회 생략(빈 맵), use case 가 원문 사용.

**Rationale**:
- 음식명 번역(`findFoodNameTranslation`)과 같은 결을 유지하되, 두 kind 를 별도 2쿼리로 나누지 않고 `findByFoodIdAndLangCode` 한 번으로 두 행을 받아 N+1 을 피한다.
- 반환 맵에 없는 kind 는 use case 에서 ko 원문으로 폴백 → 필드별 독립 폴백을 자연스럽게 표현.

**Alternatives considered**:
- **kind 별 2메서드/2쿼리**: 호출·쿼리 2배, 이점 없음. 기각.

## R4. mock 콘텐츠

**Decision**: 간단·자세 설명의 ko 원문과 9개 언어 번역은 기존 음식명 번역 seed 와 동일하게 **데모용 placeholder 텍스트**로 채운다.

**Rationale**: 본 슬라이스는 구조(필드·다국어·폴백·seed)를 확정하는 것이 목표이고, 각 설명의 실제 편집 콘텐츠 정의는 기획 확인 대기(spec Dependencies). placeholder 로 전 경로를 검증하고, 확정 시 seed 텍스트만 교체한다.

**Alternatives considered**: 콘텐츠 확정 대기로 기능 블로킹 — 불필요. 구조·API 는 콘텐츠와 독립이므로 진행. 기각.
