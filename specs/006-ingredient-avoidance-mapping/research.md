# Phase 0 Research: 회피·주의 성분 카탈로그 DB 영속화 + 재료 매핑

본 기능의 기술 결정과 근거. spec 의 결정(성분 DB 영속화·번역 비정규화·분류 멤버십 테이블·매핑 FK·enum 공존·미지원 언어 에러 분리)을 확정한다.

## D-STORE: 성분 카탈로그 저장 — MySQL DB(JPA 엔티티), 004 enum 은 공존 유지

- **Decision**: 카탈로그를 **MySQL DB 테이블 + JPA 엔티티**로 영속화한다. 004 의 enum(`AvoidanceSubstance`·`AvoidanceSubstanceTranslations`·`AvoidanceCatalog`·`AvoidanceCategory`)은 **제거하지 않고 유지**하며, enum 데이터를 **DB 시드의 원천**으로 쓴다.
- **Rationale**:
  - 재료(`ingredient`)가 이미 MySQL 에 있어, 성분도 DB 에 있어야 **FK 로 정합 있게 매핑**하고 음식 조회 시 판정할 수 있다(004 의 "코드 문자열 참조" 방식은 DB 무결성을 못 줌 — 사용자 지적).
  - enum 유지 이유: 도메인 로직(예: #16 판정의 타입 안전 참조)에서 컴파일 상수가 유용할 수 있음. 과도기 공존으로 두고, enum 최종 제거/존치는 작업 말미·후속 판단.
  - enum↔DB **데이터 정합은 테스트로 강제**(이중 표현의 드리프트 방지).
- **Alternatives considered**:
  - *enum 즉시 제거(DB 단일 출처)*: 가장 깔끔하나 도메인 참조 가능성 + 헌법 원칙 V 단서 조정이 묶여 위험. 후속으로 분리(사용자 결정).
  - *enum 유지 + 매핑은 코드 문자열(004 plan 의 옛 안)*: FK 무결성 없음 — 사용자가 명시적으로 DB 영속화·FK 를 원함. 폐기.

## D-TRANS: 9개 번역 저장 — 성분 테이블에 언어별 컬럼(비정규화)

- **Decision**: 번역을 **`avoidance_substance` 테이블의 언어별 컬럼**(`name_zh_hans`·`name_en`·`name_ja`·`name_zh_hant`·`name_vi`·`name_id`·`name_th`·`name_ru`·`name_es`)으로 비정규화 저장한다. ko 는 원문 컬럼 `korean_name`. 폴백: 컬럼이 NULL → `korean_name`.
- **Rationale**:
  - 성분은 **정적·소량(81행)·고정 9언어**라 행-per-언어 정규화의 이점(가변 언어 확장)이 거의 없고, 컬럼 방식이 조회 시 조인 0·단순(사용자 제안).
  - ko 를 번역 테이블에 넣지 않는 기존 정책(음식명)과 일관 — ko 는 `korean_name` 원문.
- **Alternatives considered**:
  - *행-per-언어 정규화(`avoidance_substance_name_translation`, food 패턴)*: 일관성은 있으나 정적 81×9 엔 과함. 사용자가 컬럼 방식 선호. 폐기(단 의도적 컨벤션 이탈로 기록).
- **Impact**: 기존 `food_name_translation`·`ingredient_name_translation`(정규화)과 **다른 패턴** — data-model 에 의도적 이탈로 명시. 새 언어 추가 시 컬럼·엔티티·시드 동시 변경 필요(정적이라 수용).

## D-CATEGORY: 분류 — enum(3종) 값 유지 + 성분↔분류 멤버십 테이블

- **Decision**: 분류 값은 `AvoidanceCategory` **enum(3종) 유지**, VARCHAR 로 저장. 성분↔분류(1~3개, 다대다) 멤버십은 **별도 테이블** `avoidance_substance_category(substance_id, category)`, `UNIQUE(substance_id, category)`.
- **Rationale**:
  - 분류는 고정 3개 라벨이라 `RiskLevel`·`EntityStatus` 처럼 enum→VARCHAR 저장이 관례. "성분 카탈로그 enum 탈피" 대상은 81종 성분(내용물 큰 것)이지 3개 분류 라벨이 아님.
  - 가변 1~3 멤버십은 컬럼 비정규화 불가(1NF) → 조인 테이블 필수.
- **Alternatives considered**:
  - *분류도 마스터 테이블*: 3행 정적 라벨에 과함. enum + VARCHAR 로 충분. 폐기.
  - *멤버십을 성분 테이블 컬럼(category1/2/3)*: 1NF 위반·쿼리 불편. 폐기.

## D-MAP: 재료↔성분 매핑 — FK 조인 테이블

- **Decision**: `ingredient_avoidance_substance(ingredient_id FK→ingredient, substance_id FK→avoidance_substance)`, `UNIQUE(ingredient_id, substance_id)`. 다대다. 재료·성분 모두 FK(둘 다 DB 에 있으므로 무결성 확보 — D-STORE 의 직접 효과).
- **Rationale**: 성분이 DB 로 오면서 매핑이 **양방향 FK** 가 된다 — 옛 plan 의 "코드 문자열 + 동기화 테스트" 우회가 불필요해지고 DB 가 무효 참조를 직접 차단(사용자가 원한 무결성).
- **조회**: 재료 id 집합 → `ingredient_id IN (...)` 조인 → 성분(+분류) 도메인. 미매핑 재료는 빈 집합.

## D-ARCH: 소유·배치 — 도메인 모델/port = avoidance, JPA = persistence

- **Decision**:
  - `:core:avoidance`: 도메인 모델 `AvoidanceSubstance`(class, 기존 enum 과 **이름 충돌** → 도메인 모델은 별도 이름 또는 패키지로; 아래 D-NAMING)·`AvoidanceCategory`(enum 유지)·port `AvoidanceSubstanceRepository`·`IngredientAvoidanceSubstanceRepository`. 순수(Spring/JPA 없음).
  - `:infra:persistence`(패키지 `...avoidance`): `AvoidanceSubstanceJpaEntity`(+9 번역 컬럼)·`AvoidanceSubstanceCategoryJpaEntity`·`IngredientAvoidanceSubstanceJpaEntity` + Spring Data 리포지토리 + 어댑터. 전부 `BaseEntity` 상속.
  - `:application:client`: 음식→성분 합집합 조합 `FoodAvoidanceSubstanceResolver`(원칙 II).
  - `:app:api`: Flyway 시드(스키마 owner) + enum↔시드 정합 테스트.
- **Rationale**: 원칙 IV(전 JPA 를 persistence 에)·원칙 III(도메인 port→persistence 구현→부트앱 runtimeOnly 조립)·원칙 II(컨텍스트 조합은 application). avoidance 도메인 모델은 ingredient 를 **id(Long)로만** 참조(food 타입 미import).

## D-READMODEL: enum 을 타입 통화(currency)로, 데이터는 DB 에서 읽기 (이름 충돌·중복 모델 회피)

- **문제**: enum `AvoidanceSubstance`(004) 를 유지하면서 같은 개념의 도메인 모델/엔티티가 필요 → 이름 충돌·모델 중복 위험.
- **Decision**:
  - port 반환 타입은 **기존 enum `AvoidanceSubstance` 를 그대로 사용**(새 도메인 클래스 안 만듦 → 충돌·중복 0). 어댑터가 DB `code` → `AvoidanceSubstance.valueOf(code)` 로 브리지.
  - **데이터(번역·분류 멤버십·재료 매핑)는 DB 에서 읽는다** — 즉 DB 가 진짜 read source:
    - 번역: `AvoidanceSubstanceRepository.translatedName(substance, lang)` 가 **DB 번역 컬럼**을 읽고 NULL → `korean_name`(ko) 폴백.
    - 분류 조회: `byCategory(category)` 가 **DB 멤버십 테이블**을 조인해 enum 목록 반환.
    - 매핑: `IngredientAvoidanceSubstanceRepository` 가 **매핑/성분 테이블** 조인.
  - JPA 엔티티는 `...JpaEntity` 접미사라 enum 과 이름 충돌 없음.
- **Rationale**: enum 을 유지하기로 한 이상(D-STORE), enum 을 **타입 통화(식별자)** 로 쓰면 #16/#17 이 타입 안전하게 소비하고 병렬 도메인 모델 중복이 없다. 동시에 **조회 데이터는 DB 에서** 읽어 "성분을 DB 로 영속화·조회" 라는 본 기능 목표를 실제로 충족한다(enum 의 intrinsic `categories`/`koName` 은 시드 원천일 뿐 — 조회는 DB 경로). enum↔DB 정합 테스트(D-SEED)가 둘을 일치시킨다.
- **Note**: 과도기 공존이라 "DB vs enum 단일 출처" 긴장은 남는다(D-STORE). 후속에서 enum 제거 시, port 반환을 DB 기반 도메인 모델로 교체(이름 그대로 승계 가능). 본 기능 범위: **영속·FK·시드·DB 조회**.

## D-SEED: 시드 — enum 데이터에서 생성, 정합 테스트

- **Decision**: `V5__create_avoidance_catalog_and_mapping.sql`(app:api) — 3 테이블 생성 + 81 성분(코드·ko·9번역) + 분류 멤버십 + (mock) 재료 매핑 시드. 시드 값은 **enum/`AvoidanceSubstanceTranslations` 데이터와 일치**.
- **정합 테스트**(D-SEEDVALID): enum 의 모든 성분 코드·분류·번역이 DB 시드와 일치하는지 검증(`:app:api:test` 또는 persistence H2). H2 는 Flyway off(테스트 규약)라, 정합 검증은 (a) 시드 SQL 파싱 ↔ enum 대조 또는 (b) enum→기대 시드 생성 후 비교. tasks 에서 방식 확정.
- **Rationale**: enum·DB 공존의 드리프트를 CI 에서 차단(안전 직결).

## D-LANG: 미지원 언어 에러 — 본 기능 제외(#18 후속)

- **Decision**: "미지원 언어 코드 → 에러 + 지원 목록" 은 **본 기능 범위 밖**. GitHub **#18** 로 분리. 본 기능은 **ko 폴백만**(번역 컬럼 NULL → `korean_name`).
- **Rationale**: `LanguageCode` 는 공유 커널 어휘 → 음식/스캔에도 영향. 일관 적용을 위해 별도 작업. 본 기능에 섞으면 범위·회귀가 커짐.

## D-TEST: 테스트 전략

- Kotest `BehaviorSpec`(given/when/then 한국어).
- **`:infra:persistence:test`**(H2): 성분 어댑터(코드 조회·분류·번역 컬럼·ko 폴백) + 매핑 어댑터(재료 id→성분·미매핑 빈집합·다대다·유일·소프트삭제).
- **`:application:client:test`**: 음식→성분 합집합.
- **`:app:api:test`**: enum↔DB 시드 정합 + ArchUnit 경계 회귀.
- 콘텐츠: 시드는 enum 의 현재 mock 데이터 — 확정 콘텐츠 수령 시 교체.

## 헌법 영향 (governance, 후속)

- 원칙 V 예외 단서가 "고정 reference taxonomy 는 컴파일 enum 저장 허용" 을 명시 → 본 기능은 DB 영속화(원칙 V 본문과 합치)지만 enum 도 유지. enum 최종 제거 시 단서 조정 `/speckit-constitution` 필요. 본 plan 과 분리.
