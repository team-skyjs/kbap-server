# Implementation Plan: 관리자 음식 삭제(소프트) 기능

**Branch**: `kb-277-admin-food-soft-delete` | **Date**: 2026-08-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-277-admin-food-soft-delete/spec.md`

## Summary

관리자 음식 상세 패널(`/admin/foods/list?detail={id}`)에 삭제 버튼을 추가한다. 삭제는 `AdminFoodService.deleteFood(id)`가 `BaseEntity.delete()`(status=DELETED)로 소프트 처리하고, 컨트롤러는 `POST /admin/foods/{id}/delete` 폼 제출을 받아 현재 페이지 유지 redirect 로 목록에 돌려보낸다. 확인 단계는 기존 선례(`food-images.html`)와 동일한 네이티브 `confirm()` 다이얼로그로 처리하며, 확인 문구·패널 안내 문구에 동명 재시드 누락 제약을 명시한다. `@SQLRestriction("status = 'ACTIVE'")`이 BaseEntity 레벨에서 전 조회에 적용되므로 관리자 목록·앱 사용자 조회(검색·상세·북마크·리뷰)의 노출 제외는 **기존 메커니즘으로 자동 달성** — 조회 경로 코드 변경은 없고 테스트로 확인만 한다. 스키마 변경 없음(Flyway 마이그레이션 불필요).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa·thymeleaf 관리자 페이지), Hibernate `@SQLRestriction` 소프트 삭제

**Storage**: MySQL (기존 `food` 테이블 — BaseEntity `status` 컬럼 재사용, 스키마 변경 없음)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest` + MockMvc + MySQL Testcontainers (`MySqlContainerConfig`)

**Target Platform**: `:api` web bootJar 관리자 화면 (서버 렌더 Thymeleaf)

**Project Type**: 모듈러 모놀리스 내 관리자 웹 기능 (기존 `com.kbap.api.admin` 기능 패키지 확장)

**Performance Goals**: 해당 없음 — 내부 관리자 소수 사용, 단건 삭제

**Constraints**: 삭제 확인 단계 필수(실수 방지), 삭제 후 현재 페이지 번호 유지 redirect, 동명 재시드 누락 안내 문구 표기

**Scale/Scope**: 파일 5개 수정(서비스·컨트롤러·템플릿·테스트 2종) — 신규 엔드포인트 1개, 신규 서비스 메서드 1개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 서비스 삭제 성공/미존재, 컨트롤러 redirect, 삭제 후 비노출을 실패 테스트로 먼저 작성(Red) 후 구현. |
| II. Bounded Contexts | PASS | 관리자 전용 로직은 `com.kbap.api.admin`의 `AdminFoodService`에 둔다(관리자 서비스 분리 원칙 — 공용 `FoodService` 오염 금지). 도메인 간 의존 변경 없음. |
| III. Layered Dependency Direction | PASS | api → common 방향만 사용. `AdminFoodService`가 `FoodJpaRepository`를 직접 쓰는 기존 패턴 유지. |
| IV. Persistence Ownership | PASS | 스키마 변경 없음. 상태 전이는 관리 엔티티 dirty checking(`food.delete()`), `@Transactional` 명시. 창구 서비스 신설 없음. |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠 언어 정책과 무관(관리자 화면은 한국어 고정). |

**Post-Design 재확인 (Phase 1 후)**: 위반 없음 — 신규 모듈·패키지·마이그레이션 없이 기존 admin 기능 패키지 확장에 그침.

## Project Structure

### Documentation (this feature)

```text
specs/kb-277-admin-food-soft-delete/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-food-delete.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodService.kt          # [수정] deleteFood(id) + AdminFoodDeleteResult 추가
└── AdminFoodPageController.kt   # [수정] POST /admin/foods/{id}/delete 추가

api/src/main/resources/templates/admin/
└── food-list.html               # [수정] 상세 패널에 삭제 폼(confirm) + 재시드 안내 문구 + 삭제 완료 배너

api/src/test/kotlin/com/kbap/api/admin/
├── AdminFoodServiceTest.kt          # [수정] 삭제 성공·미존재·기삭제·목록 제외 시나리오
└── AdminFoodPageControllerTest.kt   # [수정] POST delete redirect·에러 redirect 시나리오
```

**Structure Decision**: 기존 `com.kbap.api.admin` 기능 패키지 확장. 신규 파일 없음 — 서비스 메서드·컨트롤러 핸들러·템플릿 블록을 기존 파일에 추가한다. `:common`·Flyway·앱 사용자 조회 경로는 손대지 않는다.

## Complexity Tracking

> 헌법 위반 없음 — 기록할 항목 없음.
