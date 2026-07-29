# Contracts: 관리자 페이지 뷰 라우트

**Date**: 2026-07-29 | **Plan**: [../plan.md](../plan.md)

뷰 라우트는 REST 규약(`/api/v` 경로·`BaseResponse` 봉투) 대상이 아니다(research R3). 전 라우트는 HTML 응답이며, 로그인 라우트를 제외하고 `AdminPageAuthInterceptor`(ADMIN JWT 쿠키) 보호를 받는다.

## 인증 흐름

| Method·Path | 인증 | 동작 | 응답 |
|---|---|---|---|
| `GET /admin/login` | 불필요 | 로그인 폼 | 200 `admin/login` 뷰. 이미 인증된 경우 302 → `/admin` |
| `POST /admin/login` | 불필요 | `loginId`·`password` 폼 검증(계정 테이블) | 성공: ADMIN JWT 쿠키 설정 + 302 → `/admin` · 실패: 200 로그인 뷰 + 오류 메시지(자격 증명 불일치 단일 문구 — 계정 존재 여부 비노출) |
| `POST /admin/logout` | 필요 | 쿠키 만료(동일 Path) | 302 → `/admin/login` |
| `GET /admin` | 필요 | 홈 | US1: 200 홈 뷰 → US2 이후: 302 → `/admin/foods` |

- 미인증(쿠키 없음/무효·만료/USER role)으로 보호 라우트 접근 → 302 → `/admin/login`.
- 쿠키: HttpOnly·Secure·SameSite=Strict·Path=/admin, **Max-Age 미지정(세션 쿠키)** — 만료는 토큰 자체 만료를 인터셉터가 검증(TTL 설정 배관 불필요).
- **POST 라우트는 Origin 헤더가 자기 오리진과 불일치하면 거절**(무상태 CSRF 최소 방어 — SameSite=Strict 보완).
- 관리자 토큰을 회원 API(`Authorization` 헤더)에 재사용하면 거절된다(`@AuthMemberId` 리졸버 가드 — 주체 혼동 차단).

## 음식 데이터 (사이드바: 음식 데이터)

| Method·Path | 동작 | 응답 |
|---|---|---|
| `GET /admin/foods` | 적재 현황 대시보드 | 200 `admin/foods` 뷰 — model: `AdminFoodDashboardView`(total, 상태별 4종 건수, readyRatio) + query parameter 결과 배너 |
| `POST /admin/foods/seed` | 음식 시드 등록(기존 서비스 재사용) | PRG: 302 → `/admin/foods?seeded=N`(실패: `?error=<사유코드>`) — **결과는 flash 가 아닌 query parameter**(무상태 — prod api 2대에서 flash 는 HttpSession 저장이라 유실) |
| `POST /admin/foods/images` | 이미지 배치 제출(기존 서비스 재사용) | PRG: 302 → `/admin/foods?submitted=N`(대상 0건: `?submitted=0`) |

- 시드 입력은 textarea **줄 단위 파싱**(공백 줄 무시). 빈 입력 → `?error=...` 리다이렉트, 데이터 무변경 (spec US3-4).
- 폼 처리 중 예외는 뷰 컨트롤러가 잡아 `?error=...` 로 리다이렉트 — 전역 JSON 예외 응답을 노출하지 않는다.
- 기존 REST `POST /api/v1/admin/foods`·`/api/v1/admin/foods/images` 는 **무변경 유지**.

## 회원 관리 (사이드바: 회원 관리)

| Method·Path | 동작 | 응답 |
|---|---|---|
| `GET /admin/members?page={n}` | 회원 목록 — id desc, 페이지당 20, **page 1-based** | 200 `admin/members` 뷰 — model: `AdminMemberPageView`. 범위 초과 → 빈 목록, 음수/비숫자 → 1페이지 보정(오류 미노출) |
| `GET /admin/members/{id}` | 회원 상세 — 프로필 이미지는 공개 URL(`ImageUrls.resolve`)로 해석 | 200 `admin/member-detail` 뷰. 미존재/탈퇴 id → 200 안내 화면(목록 복귀 링크) |

## 공통 레이아웃 계약

- 전 화면(로그인 제외)은 공통 fragment 레이아웃 사용: 좌측 사이드바(음식 데이터·회원 관리, 현재 위치 활성 표시) + 우측 콘텐츠.
- 뷰포트 768px(아이패드 미니 세로)에서 가로 스크롤 없이 전 기능 동작(spec FR-011·SC-005).
- 디자인 토큰은 `static/assets/admin.css`(URL `/assets/admin.css` — `/admin/**` 인터셉터 범위 밖이라 미인증 로그인 화면에서도 로드됨) 단일 출처.
