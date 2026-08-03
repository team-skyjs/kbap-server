# Research: 관리자 음식 삭제(소프트)

Technical Context 에 NEEDS CLARIFICATION 없음 — 아래는 설계 결정과 근거 기록이다.

## R1. 삭제 엔드포인트 형태

- **Decision**: `POST /admin/foods/{id}/delete` (관리자 페이지 폼 제출, redirect 응답).
- **Rationale**: HTML 폼은 GET/POST 만 지원하므로 HTTP DELETE 불가. 관리자 화면은 `/api/v1` 규약 밖(비즈니스 API 아님 — 기존 `/admin/**` 페이지 컨트롤러 선례)이며, 같은 컨트롤러의 수정 선례가 `POST /admin/foods/{id}` 다. 수정과 구분되는 동사 접미 경로로 명확히 한다.
- **Alternatives considered**: REST `DELETE /api/v1/admin/foods/{id}` — 관리자 화면은 서버 렌더 폼 기반이라 JS fetch 를 새로 도입해야 함. 기각. `POST /admin/foods/{id}?action=delete` — 수정 핸들러와 파라미터 분기가 얽혀 오독 위험. 기각.

## R2. 소프트 삭제 메커니즘·비노출 보장

- **Decision**: `food.delete()`(BaseEntity, status=DELETED) + dirty checking. 조회 경로 코드는 일절 수정하지 않는다.
- **Rationale**: `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 이 전 엔티티 조회(파생 쿼리·`findById` 로드 포함)에 상시 적용된다(CLAUDE.md 고정 규약 — "모든 조회는 자동으로 ACTIVE 만 본다"). 사용자 경로별 확인 결과:
  - 검색·상세: `FoodService`/`FoodJpaRepository` 경유 — 자동 제외. native 쿼리(`findRandomReady` 등)도 `f.status = 'ACTIVE'` 명시 확인.
  - 북마크 목록: `BookmarkService.getBookmarkPage` 가 `getReadyFoodsByIds` 결과에 `mapNotNull` — 삭제된 음식 항목은 조용히 drop, 목록은 정상 동작.
  - 리뷰 목록: `ReviewService.getFoodReviewPage` 가 `foodService.getReadyFood(foodId)` 선행 — 삭제된 음식의 리뷰 목록은 FOOD not found 로 처리(음식 진입점 자체가 사라지므로 정상).
  - 참조 데이터(북마크·리뷰·스캔 이력 row)는 FK 가 ON DELETE 없는 소프트 삭제 구조라 그대로 보존.
- **Alternatives considered**: 삭제 시 연관 북마크·리뷰 일괄 소프트 삭제 — 스펙(FR-003)이 참조 데이터 보존을 요구하고, 노출은 이미 차단되므로 불필요한 확산. 기각.

## R3. 이미 삭제된/미존재 음식 처리

- **Decision**: `foodRepository.findById(id)` 가 null 이면 `AdminFoodDeleteResult.NOT_FOUND` — 미존재와 기삭제를 구분하지 않고 동일하게 not-found 로 처리.
- **Rationale**: `@SQLRestriction` 때문에 삭제된 row 는 `findById` 로도 보이지 않아 두 경우가 자연히 합류한다. 기존 `updateFood` 의 NOT_FOUND 처리와 대칭.
- **Alternatives considered**: 기삭제 구분 응답 — native 쿼리로 status 무시 조회 필요, 관리자 가치 없음. 기각.

## R4. 확인 단계(실수 방지) UI

- **Decision**: 삭제 폼에 `onsubmit="return confirm('...')"` 네이티브 다이얼로그. 확인 문구에 음식명과 동명 재시드 누락 경고를 포함하고, 패널의 삭제 버튼 옆에도 상시 안내 문구를 둔다.
- **Rationale**: 같은 admin 화면의 기존 선례(`food-images.html` 배치 제출 confirm)와 동일 패턴 — 추가 JS·모달 컴포넌트 없이 브라우저 네이티브로 충분. FR-007(재시드 안내)은 confirm 문구 + 상시 문구 이중으로 충족.
- **Alternatives considered**: 2단계 서버 렌더 확인 페이지 — 왕복 1회 추가·상태 전달 복잡. 기각. 커스텀 모달 — admin 화면에 모달 인프라 없음, 과설계. 기각.

## R5. 삭제 후 이동·피드백

- **Decision**: 성공 시 `redirect:/admin/foods/list?page={page}&deleted={id}`, 실패 시 `...&error=not-found`. 앵커(`#food-{id}`) 없음.
- **Rationale**: FR-004(현재 페이지 유지). 삭제된 row 는 목록에서 사라지므로 앵커 점프 대상이 없고, 관리자 UI 선호(GET 앵커 점프 금지)와도 일치. `deleted` 파라미터로 기존 `updated` 배너와 같은 패턴의 완료 배너 표시. 마지막 페이지 유일 항목 삭제 시 빈 페이지가 표시될 수 있으나 기존 empty-state 카드가 처리(스펙 Edge Case 허용).
- **Alternatives considered**: 삭제 후 1페이지로 이동 — 정리 작업 연속성(스펙 US1) 훼손. 기각.

## R6. 테스트 전략

- **Decision**: 기존 두 테스트 파일 확장 — `AdminFoodServiceTest`(삭제 성공 → status 전이·목록/상세 제외, 미존재 NOT_FOUND, 기삭제 NOT_FOUND), `AdminFoodPageControllerTest`(관리자 인증 하 POST → redirect 검증, 삭제 후 목록/사용자 조회 비노출 확인). Kotest BehaviorSpec + MockMvc + Testcontainers, 기존 스타일 그대로.
- **Rationale**: 헌법 I(Test-First) — DoD 가 요구하는 "삭제 성공 + 미존재 삭제 시도" 를 Red 로 먼저 작성. 사용자 경로 비노출(FR-005)은 R2 의 기존 메커니즘 확인이므로 대표 경로 1~2개 assertion 으로 충분(전 경로 E2E 중복 테스트는 과잉).
- **Alternatives considered**: 북마크·리뷰·스캔 전 경로 통합 테스트 신설 — 기존 `@SQLRestriction` 공통 동작의 중복 검증. 기각.
