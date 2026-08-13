# Implementation Plan: 관리자 음식 목록 화면 개편 — 카드 그리드·상태 필터·상세 모달

**Branch**: `kb-287-admin-food-grid` | **Date**: 2026-08-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-287-admin-food-grid/spec.md`

## Summary

관리자 음식 목록(`/admin/foods/list`)을 행 목록 → **정사각형 카드 그리드**(썸네일·음식명·상태 배지)로 개편하고, **고정 높이 + 내부 스크롤** 뷰포트를 도입한다. **`content_status` 필터**(`status` 쿼리 파라미터, 알 수 없는 값은 무시)를 음식명 검색과 AND 로 결합하고, 상세를 우측 패널 → **네이티브 `<dialog>` 모달**로 전환한다(기존 read-only 오픈·편집 토글·삭제 확인·유효성 오류 재오픈 계약 유지). 버튼 4종(편집·저장·취소·삭제)은 공통 `.btn` 규격 + 역할별 색 변형으로 통일하고, 읽기 모드의 JSON 3종은 자체 소형 JS 하이라이터로 색상 표시한다(편집 모드는 기존 textarea 유지 — 값 유실 방지). 새 외부 의존성·빌드 도구·DB 마이그레이션 없음. 서버 변경은 `AdminFoodService.getFoodPage` 의 status 분기 + `AdminFoodSummaryView.imageUrl` + `FoodJpaRepository` 파생 쿼리 2개 + 컨트롤러 `status` 파라미터 스레딩이 전부다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Thymeleaf(서버 렌더 관리자 화면), 기존 `admin.css` 단일 스타일시트. **신규 의존성 없음** — 모달은 네이티브 `<dialog>`(대시보드 `report-modal` 선례), JSON 하이라이팅은 인라인 소형 JS.

**Storage**: MySQL (기존 `food` 테이블 — 스키마 변경·마이그레이션 없음. `content_status` 컬럼 기존 존재)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`/MockMvc + MySQL Testcontainers (기존 `AdminFoodListControllerTest`·`AdminFoodPageControllerTest` 확장)

**Target Platform**: 데스크톱 브라우저 (관리자 SSR 화면, `body { min-width: 768px }` 유지)

**Project Type**: 웹 서비스 (모듈러 모놀리스 — 이번 변경은 `:api` 모듈 admin 기능 + `:common` 리포지토리 파생 쿼리)

**Performance Goals**: 목록 페이지 로드 시 추가 쿼리 없음(기존 1 page 쿼리 유지 — status 는 동일 쿼리의 조건 분기). 썸네일은 CDN public URL `<img loading="lazy">`.

**Constraints**: 새 프론트엔드 라이브러리·빌드 스텝 금지. 세 JSON 필드는 편집 모드에서 반드시 form 필드로 제출되어야 함(누락 시 `defaultValue=""` → 번역 전체 삭제). 앱 사용자 API 무영향.

**Scale/Scope**: 페이지당 200건(기존 `LIST_PAGE_SIZE` 유지), 화면 1개(food-list.html) + CSS + 서비스/컨트롤러/리포지토리 소폭 수정.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 서비스 필터 분기·컨트롤러 파라미터·템플릿 렌더링 모두 기존 테스트 스타일(BehaviorSpec + MockMvc 렌더 검증)로 Red 선행 가능. tasks 에서 테스트 task 를 구현 task 앞에 배치한다. |
| II. Bounded Contexts | ✅ | 관리자 전용 로직은 `com.kbap.api.admin`(Admin*Service — 관리자 서비스 분리 원칙)에 유지. 도메인 간 의존 변화 없음. |
| III. Layered Dependency Direction | ✅ | `:api` → `:common` 방향 그대로. `FoodJpaRepository` 에 파생 쿼리 2개 추가는 `:common` 소유 리포지토리 확장일 뿐 역방향 의존 없음. |
| IV. Persistence Ownership | ✅ | 엔티티·스키마 변경 없음. AdminFoodService 가 리포지토리 직접 사용(기존 구조 유지). 파생 쿼리는 소유 도메인 패키지(`common.domain.food`)에 추가. 트랜잭션 경계는 기존 `@Transactional(readOnly = true)` 유지. |
| V. Language Policy | ✅ | 관리자 화면은 한국어 고정(정책 대상 아님). 음식 콘텐츠 번역 데이터는 읽기/편집 표시만 바뀌고 정책 무관. |

위반 없음 → Complexity Tracking 불요.

**Post-Phase-1 재평가**: 설계 산출물(data-model·contracts) 확인 후에도 위반 없음 — 신규 계층·신규 모듈·도메인 로직 이동 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-287-admin-food-grid/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-food-list-pages.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodPageController.kt   # 수정 — status 파라미터 수신·스레딩(listRedirect 포함)
└── AdminFoodService.kt          # 수정 — getFoodPage(page, q, status) 4분기,
                                 #        AdminFoodSummaryView.imageUrl 추가,
                                 #        AdminFoodListPageView.status 추가

api/src/main/resources/
├── templates/admin/food-list.html   # 전면 개편 — 카드 그리드 + <dialog> 모달 + 인라인 JS
└── static/assets/admin.css          # 추가 — .food-grid*, .food-modal*, .btn 변형, .json-view 색

common/src/main/kotlin/com/kbap/common/domain/food/
└── FoodJpaRepository.kt         # 수정 — findByContentStatus / findByKoreanNameContainingAndContentStatus

api/src/test/kotlin/com/kbap/api/admin/
├── AdminFoodListControllerTest.kt   # 수정 — 그리드/필터/모달 렌더 검증 재작성
└── AdminFoodPageControllerTest.kt   # 수정 — status 스레딩(저장·삭제 리다이렉트) 검증
```

**Structure Decision**: 기존 파일 구조 그대로 — 신규 클래스 없음(뷰 모델 필드 추가·파생 쿼리 추가·템플릿/CSS 개편). 관리자 로직은 `com.kbap.api.admin` 에 유지한다.

## Complexity Tracking

위반 없음 — 해당 없음.
