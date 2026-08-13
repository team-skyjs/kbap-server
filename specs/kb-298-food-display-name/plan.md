# Implementation Plan: 음식 표시용 이름(display name) 분리

**Branch**: `kb-298-food-display-name` | **Date**: 2026-08-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-298-food-display-name/spec.md` (Jira: KB-298)

## Summary

`food.korean_name` 은 유니크 제약(`uq_food_korean_name`)이 걸린 **정규화 match key**(한글 음절만 — `KoreanMenuNameNormalizer.matchKey`)를 겸하고 있어, 스캔 DB miss 적재 음식이 공백 없는 이름으로 노출된다. **표시 전용 `display_name` 컬럼을 추가**해 원본 표기를 보존하고, `korean_name` 은 기존대로 match key 로 유지한다. 한국 메뉴명을 노출하는 모든 응답 경로(`Food.displayName(lang)`의 ko 베이스 + `koreanName()` 소비처)를 `display_name` 값으로 교체하며, 응답 필드 구조는 바꾸지 않는다. 기존 행은 `korean_name` 값으로 백필한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Flyway(+mysql), springdoc

**Storage**: MySQL (`food` 테이블 — 스키마 owner `:api` Flyway). 컬럼 추가 1건 + 백필

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit5 플랫폼, 통합 테스트는 MySQL Testcontainers(Flyway on, `ddl-auto=validate`)

**Target Platform**: Linux 서버 (api bootJar), batch bootJar 은 읽기 소비만

**Project Type**: 모듈러 모놀리스 — `:common`(Food 엔티티·도메인 서비스) / `:api`(scan·food·admin 기능 패키지) / `:batch`(콘텐츠 채움 파이프라인)

**Performance Goals**: 해당 없음 (컬럼 1개 추가·값 교체 — 쿼리 형태 불변)

**Constraints**: 응답 필드 구조·필드명 무변경(클라이언트 무수정 호환), 중복 판정 기준(match key) 무변경, 프로덕션 적용 전 마이그레이션은 독립 실행 가능해야 함(out-of-order 전제)

**Scale/Scope**: `Food` 엔티티 + Flyway 1건 + 소비처 교체(scan·food 상세/목록/검색·bookmark·home·community·admin·batch LLM 프롬프트) + 테스트 보강

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (계획) | tasks 단계에서 각 변경을 Red→Green 으로 진행. 신규 동작(원본 표기 저장·first-write-wins·백필·응답 교체)마다 실패 테스트 선행 |
| II. Bounded Contexts | PASS | 변경은 food 도메인(`common.domain.food`)과 그 소비 api 기능 패키지·batch 에 국한. 도메인 간 의존 방향 변화 없음 |
| III. Layered Dependency Direction | PASS | 모듈 의존 변화 없음(api·batch→common 그대로) |
| IV. Persistence Ownership | PASS | 컬럼·엔티티 변경은 `common.domain.food` 소유, 마이그레이션은 `:api` Flyway(owner). JPA 연관 추가 없음 |
| V. Language Policy | PASS | `display_name` 은 ko 원문의 표기 보존 — `LocalizedText` 의 ko 베이스가 정규화명→표시명으로 바뀔 뿐, 번역·폴백 3분기 불변 |

**Post-design re-check**: PASS — 설계 산출물(data-model·contracts)에서 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-298-food-display-name/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── api-responses.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V2026.08.05.<HH.mm.ss>__add_food_display_name.sql   # 신규 — 컬럼 추가+백필

common/src/main/kotlin/com/kbap/common/domain/food/
├── model/Food.kt              # displayName 프로퍼티·incomplete(2인자)·localizedName ko 베이스 교체·koreanName() 폐기
├── FoodService.kt             # getDetail koreanName 매핑·KO 검색 키워드 정규화
├── FoodRepositoryCustomImpl.kt # upsertIncomplete 에 display_name 추가(중복 시 기존 유지)
└── dto/FoodSummaryView.kt     # koreanName 필드 값 → displayName

api/src/main/kotlin/com/kbap/api/
├── scan/ScanService.kt        # miss 적재 시 원본 표기 전달, matched 응답 koreanName → displayName
├── scan/ScanApi.kt            # swagger 문구 갱신
├── food/FoodImageBatchCollectService.kt # 이미지 프롬프트 이름 → displayName
└── admin/AdminFoodService.kt  # 수정=display 교정+match key 재정규화, seed=원본 표기 보존, 응답 값 교체

batch/src/main/kotlin/com/kbap/batch/content/
└── FoodContentItemProcessor.kt # LLM 호출 이름 3곳 → displayName

api/src/test/kotlin/...        # scan·food·admin 관련 스펙 보강(기존 시드는 DEFAULT '' + 읽기 폴백으로 무수정 흡수)
```

**Structure Decision**: 기존 구조 그대로 — 신규 파일은 Flyway 마이그레이션 1개뿐이고 나머지는 기존 파일 수정이다.

## 핵심 설계 결정 (요약 — 상세는 research.md)

1. **`korean_name` 은 그대로 match key** — 컬럼 리네임(`match_key`) 없이 `display_name` 만 추가한다(리네임은 유니크 제약·upsert·조회 전면 수정을 유발, KB-298 범위 밖).
2. **컬럼은 `VARCHAR(255) NOT NULL DEFAULT ''` + 읽기 폴백** `displayName.ifBlank { koreanName }` — 흩어진 raw INSERT 테스트 시드를 무수정으로 흡수하고, 빈 표시명이 화면에 노출될 가능성을 이중으로 차단.
3. **first-write-wins** — upsert `on duplicate key update id = id` 가 이미 기존 행을 보존하므로 FR-006 은 추가 코드 없이 충족.
4. **`Food.displayName(lang)` 메서드는 유지**하고 `localizedName()` 의 ko 베이스만 표시명으로 교체 — 소비처(FoodService·FoodSummaryView·Scan·Community) 다수가 자동으로 표시명을 받는다. `koreanName()` 액세서는 폐기하고 소비처는 표시명 프로퍼티를 직접 쓴다.
5. **KO 검색 키워드는 matchKey 정규화 후 `korean_name` LIKE** — 사용자가 화면 표기("김치 찌개")대로 검색해도 매칭되도록 키워드만 정규화(공백 유무 불문 매칭). 검색 대상 컬럼·쿼리 형태는 불변.
6. **관리자 음식명 수정 = 표기 교정** — 입력을 `display_name` 에 저장하고 `korean_name` 은 `matchKey(입력)` 로 재정규화(중복 검사도 match key 기준). `korean_name = matchKey(display_name)` 불변식 유지.
7. **batch LLM 호출은 표시명 사용** — 공백 있는 원본 표기가 번역·설명 생성 품질에 유리하고 읽기 전용이라 위험 없음.

## 구현 중 확정된 변경 (플랜 대비)

1. **`Food.incomplete(koreanName, displayName = koreanName)`** — 기본값 있는 2번째 인자로 확장했다. 테스트 호출부 30곳이 1인자 호출이라, 기본값이 그 전부를 무수정으로 흡수하면서 표기 보존이 필요한 2곳(스캔 적재·관리자 시드)만 명시 전달한다.
2. **이미지 프롬프트는 `FoodImageBatchSubmitService`**(LLM 프롬프트 생성 지점)를 바꿨다. 플랜이 지목한 `FoodImageBatchCollectService` 의 이름 사용처는 **SHA-256 스토리지 키 해시 입력**이라 표기와 무관하며, 오히려 match key 가 더 안정적인 입력이라 그대로 뒀다.
3. **관리자 목록 검색 키워드도 match key 정규화**(플랜 외 추가) — `findByKoreanNameContaining` 이 match key 컬럼을 보므로, 정규화 없이는 운영자가 화면 표기("김치 찌개")로 검색할 때 못 찾는 회귀가 생긴다.
4. **`FoodServiceTest` 의 `_` 와일드카드 스펙 1건 의미 갱신** — `korean_name` 에 `_` 가 든 음식을 직접 시드하던 테스트인데, KB-298 이후 모든 쓰기가 match key 로 정규화되므로 그런 행은 생길 수 없다. LIKE 이스케이프 보장 자체는 정규화 결과가 blank 인 경우(영문·기호 검색어)에 원 키워드로 폴백하며 유지되고, 기존 `50%`·백슬래시 스펙이 계속 커버한다.
