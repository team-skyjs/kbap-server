# Implementation Plan: v2 스캔 응답 기피성분 겹침 표시

**Branch**: `kb-332-scan-avoidance-overlap` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-332-scan-avoidance-overlap/spec.md` (Jira: KB-332)

## Summary

v2 스캔 응답(`ScanV2Response.ItemRiskResponse`)의 각 메뉴 항목에 회원 기피성분 전체 목록을 표시명·겹침 여부·경고 수준과 함께(`avoidances: [{code, name, overlapped, riskLevel}]`) 추가한다. 겹침 판정은 `Food.overlappedIngredients`(기존 `overallRisk` 와 대칭) 도메인 메서드가 소유하고, 표시명(`Ingredient.displayName(lang)`)과 성분별 경고(`FoodIngredient.riskLevel()`)는 음식 상세(`FoodService.getDetail`)와 동일 코드 경로를 재사용한다. `ScanService` 는 조립만 한다(카탈로그 `findByCodeIn` 스캔당 1건 추가). DB 스키마 변경 없음. v1 응답은 불변.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi — 신규 의존성 없음

**Storage**: MySQL (변경 없음 — Flyway 마이그레이션 불필요, 기존 `food.ingredients`·member 프로필 기피성분 재사용)

**Testing**: Kotest BehaviorSpec (JUnit 5 platform) + Spring `@SpringBootTest`/MockMvc + MySQL Testcontainers

**Target Platform**: Linux 서버 (`:api` bootJar)

**Project Type**: web-service (모듈러 모놀리스 — 이번 변경은 `:common`(Food 도메인 메서드) + `:api`(scan 기능 패키지)만)

**Performance Goals**: 스캔 응답 시간 체감 불변 — 추가 비용은 성분 카탈로그 조회 1건(스캔당, PK in 쿼리)뿐, 외부 호출 추가 없음

**Constraints**: 기존 v2 응답 필드 무변경(필드 추가만), v1 응답 계약 불변, `X-API-Version` 매핑 구조 불변

**Scale/Scope**: 응답 DTO 필드 1개 + 도메인 메서드 1개 + 조립 로직 — 항목당 기피성분 ≤81종(카탈로그 전체 상한)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | Food 도메인 단위 테스트 → ScanService/web 통합 테스트를 구현 전 Red 로 작성(tasks 에서 강제). |
| II. Bounded Contexts | PASS | 겹침 판정은 `common.domain.food`(Food 메서드), 회원 기피성분은 기존 `MemberService.getAvoidedCodes` 경유 — 신규 도메인 간 의존 없음(scan→food·member 는 기존 방향). 응답 조립은 `com.kbap.api.scan`. |
| III. Layered Dependency Direction | PASS | api → common 방향만 사용. seam·infra 변경 없음. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·스키마 변경 없음. 도메인 로직(겹침 판정)은 엔티티(Food) 메서드로 — `overallRisk` 와 동일 패턴. |
| V. Language Policy | PASS | 성분 표시명은 카탈로그 DB(단일 출처)의 번역을 `Ingredient.displayName(lang)` 로 해석 — ko 원문 + 번역 부재 시 ko 폴백, 음식 상세와 동일 경로. enum 은 식별자(code)로만 사용. lang 검증·폴백 정책 변경 없음. |

**Post-Phase 1 재평가**: PASS — 설계 산출물(data-model·contracts)에 위반 요소 없음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-332-scan-avoidance-overlap/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── scan-v2-avoidances.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/food/model/
└── Food.kt                          # [수정] overlappedIngredients(avoidedCodes) 도메인 메서드 추가

api/src/main/kotlin/com/kbap/api/scan/
├── ScanService.kt                   # [수정] 항목별 avoidances 조립 — 기존 avoidedCodes 재사용 +
│                                    #        IngredientJpaRepository.findByCodeIn 으로 표시명 카탈로그 로드(스캔당 1건)
├── ScanResult.kt                    # [수정] ItemRiskResult.avoidances(기본값 emptyList) + AvoidanceOverlap(code·name·overlapped·riskLevel)
├── ScanV2Response.kt                # [수정] ItemRiskResponse.avoidances + AvoidanceOverlapResponse(@Schema)
└── (ScanResponse.kt 는 손대지 않음 — v1 계약 불변)

common/src/test/kotlin/com/kbap/common/domain/food/model/
└── FoodTest.kt (또는 기존 Food 스펙) # [추가] overlappedCodes 단위 스펙

api/src/test/kotlin/com/kbap/api/scan/
└── 기존 스캔 v2 통합 스펙            # [추가] 겹침·미겹침·미매칭·기피 미등록 케이스
```

**Structure Decision**: 모듈러 모놀리스 기존 구조 그대로 — `:common` 의 food 도메인 모델에 판정 메서드, `:api` 의 scan 기능 패키지에 조립·응답. 신규 파일 없이 기존 4개 파일 수정 + 테스트.

## Complexity Tracking

해당 없음 — 헌법 위반 없음.
