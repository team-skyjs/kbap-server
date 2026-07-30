# Contract: 관리자 대시보드 페이지 (kb-264)

REST API 가 아닌 서버렌더 관리자 페이지 — 계약은 라우트·모델 속성·렌더 결과로 정의한다.
(관리자 페이지는 `/api/v` 경로 규약·`BaseResponse` 봉투 대상이 아니다 — 기존 `/admin/**` 관례 유지.)

## GET /admin/foods (기존 라우트 확장)

- **인증**: 기존 `AdminPageAuthInterceptor`(ADMIN 역할 쿠키) 그대로 — 미인증 시 로그인 리다이렉트(변경 없음).
- **모델 속성**:

| 속성 | 타입 | 설명 |
|------|------|------|
| `dashboard` | `AdminFoodDashboardView` | **기존 유지** — 음식 적재 현황(total·incomplete·pendingImage·pendingReview·ready·readyRatio) |
| `metrics` | `AdminDashboardMetricsView` | **신규** — 총 가입자 수 + 주간 3지표 |

- **`metrics` 계약**:
  - `totalActiveMembers`: `member_status = ACTIVE` 회원 수 (≥ 0)
  - `weeklyScans` / `weeklyNewFoods`: 각 **정확히 7원소**, 조회일 기준 6일 전 → 오늘 오름차순, 누락 날짜 0. 각 원소: `date` + `dayLabel`(월…일) + `count` + `heightPct`
  - `llmCostDaily` (2026-07-30 개정 — 차트 → 게시판 리스트+모달): **정확히 7원소, 최신순(오늘 → 6일 전)**. 각 원소: `date` + `dayLabel` + `callCount`(호출 횟수) + `costUsd`(합계, 소수 정밀도 유지) + `models[]`(모델별 호출·입력/출력 토큰·비용 — 비용 내림차순, 기록 없는 날은 빈 리스트)
- **렌더 결과** (`admin/foods` 템플릿):
  - 기존 적재 현황 카드·READY 비율 영역 변경 없음 (SC-003)
  - 신규: 총 가입자 수 스탯 카드 1개 + 바 차트 2개(스캔·신규 음식, 막대 7개 + 요일 라벨 + 값 표기)
  - LLM 비용: 날짜·요일·호출 횟수·비용 컬럼의 **게시판 리스트(테이블)** + 행별 "레포트" 버튼 → **네이티브 `<dialog>` 모달**(해당 날짜 총 호출·총 비용 + 모델별 상세 테이블)
  - 전체 데이터가 0 이어도 차트 골격(7일 축)·리스트 7행이 렌더된다 (edge case)

## 비-계약 (변경 금지 확인용)

- `GET /admin` → `redirect:/admin/foods` 유지
- 기존 `/admin/foods/list`·`/admin/foods/seed`·`/admin/foods/images`·`/admin/members` 라우트·모델 무변경
