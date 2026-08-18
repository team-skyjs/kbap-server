# Implementation Plan: 회원 프로필 diet 카테고리 복수 선택

**Branch**: `kb-340-member-diet-categories` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-340-member-diet-categories/spec.md`

## Summary

회원 프로필에 diet 카테고리 태그(0~15종 집합)를 저장·복원한다. `DietCategory` enum 을 `:common` ingredient 도메인으로 승격하고, `member.diet_categories`(json, DEFAULT 빈 배열) 컬럼과 `MemberProfile.dietCategories: Set<DietCategory>` 필드를 추가하며, 온보딩(1.0/1.1)·프로필 수정(DTO 2벌)·내 프로필 조회에 additive 필드로 반영한다. 검증(미지원 값 400 MEMBER-011)은 기존 프로필 필드들과 동일하게 `MemberProfile` companion 소유. **회피 재료·위험도 판정은 일절 무변경** — diet 는 저장·복원 전용 태그다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa), Flyway — 신규 의존 없음

**Storage**: MySQL `member` 테이블 — json 컬럼 1개 추가(`avoidance_substance_codes` 와 동형, DEFAULT (JSON_ARRAY()) 로 백필 불필요)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers(@SpringBootTest+MockMvc)

**Target Platform**: `:api` + `:common`(enum 이동·엔티티·값 객체) — batch 무관

**Project Type**: web-service — 기존 프로필 기능 확장

**Performance Goals**: 해당 없음 — 프로필 단건 읽기/쓰기에 필드 추가

**Constraints**: additive 무버전 계약(KB-334 선례) · 기존 회원/클라이언트 회귀 0(SC-002) · 판정 경로 무개입(FR-004)

**Scale/Scope**: enum 이동 1 + 마이그레이션 1 + 파일 ~9개 수정 + 테스트 — 소형

## Constitution Check

*GATE: 통과(위반 없음). Phase 1 설계 후 재평가 — 동일.*

- **I. Test-First**: 온보딩·수정·조회 시나리오 Red 선작성 후 구현. 통과.
- **II. Bounded Contexts**: `DietCategory` 는 ingredient 컨텍스트 소속으로 승격 — member→ingredient 방향은 `MemberProfile`→`IngredientCode` 로 이미 허용 맵에 존재(ModuleBoundaryTest 수정 불필요). 통과.
- **III. Layered Dependency Direction**: api→common 방향 유지. api 소비 전용이던 enum 이 common 도메인 참조 대상이 되어 :common 으로 이동 — 배치 기준("api 밖이 컴파일 의존하는가") 충족. 통과.
- **IV. Persistence Ownership**: 컬럼·검증·updatedWith 규칙 전부 member 도메인(엔티티·값 객체)이 소유. JPA 연관 없음(문자열 배열 컬럼). Flyway 가 스키마 owner. 통과.
- **V. Domain Content Language Policy**: diet 카테고리는 code 로 주고받는 식별자(콘텐츠 아님 — koreanName 은 개발자 가독성용). 검증은 요청 경계+값 객체. 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-340-member-diet-categories/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 5건(enum 승격·JSON 컬럼·검증/에러코드·계약 규칙·판정 무개입)
├── data-model.md        # Phase 1 — Member/MemberProfile 확장·enum 이동·Flyway
├── quickstart.md        # Phase 1 — 수동 검증 시나리오
├── contracts/
│   └── member-diet-categories.md  # 온보딩·수정·조회 additive 계약
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/ingredient/model/DietCategory.kt  # api.ingredient 에서 이동(내용 무변경)
common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt            # dietCategories 컬럼 필드 + profile getter/updateProfile 반영
common/src/main/kotlin/com/kbap/common/domain/member/model/MemberProfile.kt     # dietCategories: Set<DietCategory> + validatedDiets + updatedWith
common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt                  # INVALID_DIET_CATEGORY("MEMBER-011", 400)
api/src/main/kotlin/com/kbap/api/ingredient/(diets 조회 관련 파일)              # DietCategory import 경로 갱신
api/src/main/kotlin/com/kbap/api/member/OnboardingRequest.kt                    # dietCategories: List<String> = emptyList()
api/src/main/kotlin/com/kbap/api/member/MemberProfileInput.kt                   # 필드 추가
api/src/main/kotlin/com/kbap/api/member/ProfileUpdateRequest.kt                 # dietCategories: List<String>? = null
api/src/main/kotlin/com/kbap/api/member/ProfileUpdateNoCountryRequest.kt        # 동일 추가(수정 DTO 2벌 함정)
api/src/main/kotlin/com/kbap/api/member/ProfileUpdateInput.kt                   # 필드 추가
api/src/main/kotlin/com/kbap/api/member/MyProfileResult.kt·MyProfileResponse.kt # dietCategories 반환
api/src/main/resources/db/migration/V<timestamp>__member_diet_categories.sql    # ALTER TABLE member ADD COLUMN
api/src/test/kotlin/com/kbap/api/member/(온보딩·프로필 테스트)                  # 시나리오 추가
api/src/test/kotlin/com/kbap/api/ingredient/DietCategoryMappingSyncTest.kt      # import 경로 갱신
```

**Structure Decision**: 신규 파일은 마이그레이션뿐(enum 은 이동) — 나머지는 기존 파일 확장. 검증·기본값·null 의미(누락=유지) 전부 기존 프로필 필드 규칙을 복제해 클라이언트가 새로 배울 계약이 없다.

## 구현 노트 (Phase 1 설계 확정)

- 프로필 수정의 diet 처리: `updatedWith(dietCategories = null)` = 유지, `[]` = 해제 — 스펙 가정을 기존 규칙 확인으로 확정(R4).
- `MemberService.completeOnboarding`·`updateProfile` 은 input 필드 스레딩만 — 로직 추가 없음.
- 엔티티 저장값은 검증 통과한 enum name 목록이라 `profile` getter 의 `DietCategory.valueOf` 는 안전. 방어 분기 두지 않는다(타입이 계약).
- `MemberController` 의 온보딩 1.0/1.1 두 매핑은 같은 `OnboardingRequest` 를 쓰므로 DTO 1곳 수정으로 양쪽 커버 — 수정(PATCH)만 DTO 2벌.
- OpenAPI 스냅샷 테스트가 스키마 변경으로 깨지면 갱신 절차대로 재생성.

## Complexity Tracking

> 위반 없음 — 해당 없음.
