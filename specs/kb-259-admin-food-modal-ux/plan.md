# Implementation Plan: 관리자 음식 상세 모달 UX 개선 — 목록 스크롤 유지·이미지 렌더링

**Branch**: `kb-259-admin-food-modal-ux` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-259-admin-food-modal-ux/spec.md`

## Summary

관리자 음식 목록(`/admin/foods/list`)은 상세를 `?detail=<id>` 쿼리 파라미터로 여는 SSR 전체 리로드 구조라 매번 스크롤이 최상단으로 초기화되고, 상세 모달은 `imageRef` 키 텍스트만 보여준다. 해결은 두 갈래다:

1. **스크롤 유지 — 행 anchor 방식(JS 없음).** 각 음식 행에 `id="food-<id>"` anchor를 달고, 상세 열기 링크·모달 닫기 링크·저장 후 redirect URL 전부에 `#food-<id>` fragment를 붙인다. 브라우저가 리로드 후 해당 행 위치로 자동 스크롤한다.
2. **이미지 렌더링 — 회원 상세 패턴 재사용.** `AdminFoodService`에 `kbap.storage.public-base-url`을 주입하고 `AdminFoodDetailView`에 `ImageUrls.resolve`로 해석한 `imageUrl` 필드를 추가, 모달에서 `<img>` 렌더링(없거나 로드 실패 시 플레이스홀더)한다.

DB·엔티티·공용 도메인 서비스 변경 없음 — 변경 범위는 `com.kbap.api.admin` 패키지 2파일 + `food-list.html` 템플릿 1파일이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web MVC + Thymeleaf SSR 관리자 화면), 기존 `com.kbap.common.util.ImageUrls`

**Storage**: 변경 없음 (MySQL — 이번 기능은 조회 뷰 DTO·템플릿만 변경, 마이그레이션 없음)

**Testing**: Kotest BehaviorSpec + MockMvc (`@SpringBootTest` + MySQL Testcontainers), 기존 `AdminFoodListControllerTest`·`AdminFoodPageControllerTest` 그린 유지

**Target Platform**: 내부 관리자 웹 화면 (SSR, 데스크톱 브라우저)

**Project Type**: web-service (모듈러 모놀리스 `:api` 모듈 내 admin 기능 패키지)

**Performance Goals**: 해당 없음 — 기존 페이지 렌더링 경로에 필드 1개·anchor 추가 수준

**Constraints**: 관리자 로직은 `com.kbap.api.admin`의 `Admin*Service`에 두고 공용 도메인 서비스를 오염시키지 않는다(관리자 서비스 분리 원칙). 페이지네이션 구조 변경 금지.

**Scale/Scope**: 화면 1개(음식 목록+상세 모달), 프로덕션 파일 3개 변경, 신규 파일 0개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 각 변경(모델 `imageUrl` 추가·redirect fragment·anchor 렌더링)에 대해 실패하는 MockMvc BehaviorSpec 테스트를 먼저 작성한다. 기존 admin 테스트 그린 유지가 FR-006. |
| II. Bounded Contexts | PASS | 변경은 전부 `com.kbap.api.admin` 기능 패키지 + 템플릿. 도메인 패키지(`common.domain.food`) 무변경. |
| III. Layered Dependency Direction | PASS | `api → common`(기존 `ImageUrls` util 사용) 방향만 사용. 신규 의존 없음. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·스키마 무변경. `AdminFoodService`가 이미 소유한 조회 경로에 뷰 필드만 추가. |
| V. Domain Content Language Policy | N/A | 관리자 화면은 한국어 고정 — `lang` 파라미터 없음. |

**Post-Phase 1 재점검**: 설계 산출물(data-model·contracts) 기준 위반 없음 — PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-259-admin-food-modal-ux/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── admin-food-pages.md   # SSR 페이지·redirect 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodPageController.kt   # [수정] redirect URL 에 #food-<id> fragment 추가
└── AdminFoodService.kt          # [수정] public-base-url 주입, AdminFoodDetailView.imageUrl 추가

api/src/main/resources/templates/admin/
└── food-list.html               # [수정] 행 anchor id, 상세/닫기 링크 fragment, 모달 이미지 렌더링

api/src/test/kotlin/com/kbap/api/admin/
├── AdminFoodListControllerTest.kt   # [보강] anchor·이미지 렌더링 검증 추가 (기존 그린 유지)
└── AdminFoodPageControllerTest.kt   # [보강] redirect fragment 검증 추가 (기존 그린 유지)
```

**Structure Decision**: 기존 `:api` 모듈 admin 기능 패키지 안에서만 수정한다. 신규 클래스·모듈·마이그레이션 없음.

## 설계 결정 요약

### D1. 스크롤 유지 = URL fragment(anchor) 방식

- 목록 각 행(`.food-row`)에 `id="food-<id>"` 부여.
- **상세 열기**: `@{/admin/foods/list(page=..., detail=${f.id})}` + fragment `#food-<id>` — 리로드 후 브라우저가 해당 행으로 스크롤한 채 모달 표시.
- **모달 닫기**: `@{/admin/foods/list(page=...)}` + `#food-<id>`.
- **저장 redirect**: `AdminFoodPageController.updateFood`의 5개 redirect 문자열 전부에 `#food-<id>` 부착(성공 `updated`·NOT_FOUND 포함, 검증 실패 3종은 `detail=<id>` 재오픈 + fragment). Spring `RedirectView`는 fragment 를 보존한다.
- JS `sessionStorage` 스크롤 복원 대비: 코드 0줄(JS) vs 픽셀 단위 정확도 — DoD가 "anchor 또는 스크롤 복원"으로 anchor 를 명시 허용하므로 네이티브 anchor 채택. 행 상단 정렬이라 픽셀 오차는 있으나 "보던 행"이 화면에 있으면 충족.

### D2. 이미지 렌더링 = 회원 상세와 동일 패턴

- `AdminFoodService`에 `@Value("\${kbap.storage.public-base-url:}") imagePublicBaseUrl` 주입(선례: `AdminMemberQueryService`).
- `AdminFoodDetailView`에 `imageUrl: String?` 추가 — `ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)`.
- 템플릿: `imageUrl != null`이면 `<img th:src>`, null 이면 플레이스홀더 요소. 로드 실패는 `<img onerror>` 인라인 핸들러로 이미지를 숨기고 플레이스홀더를 노출.
- `imageRef` 입력 필드(키 원문 수정)는 그대로 유지 — 이미지는 확인용 표시만 추가.

## Complexity Tracking

위반 없음 — 해당 없음.
