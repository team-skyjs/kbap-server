# Data Model: 관리자 페이지 — 음식 데이터 적재 현황·회원 관리 화면

**Date**: 2026-07-29 | **Plan**: [plan.md](plan.md)

**신규 테이블 1(`admin_account`) + Flyway 마이그레이션 1건.** 그 외 기존 영속 모델은 읽기 전용으로 소비한다. *(2026-07-29 clarify — env 자격 증명에서 계정 테이블로 개정)*

## 신규 엔티티

### AdminAccount (`common.domain.admin.model.AdminAccount`) — 신규

| 컬럼 | 타입 | 제약 |
|------|------|------|
| (BaseEntity) `id`·`status`·`created_at`·`updated_at` | — | 공통 — 소프트삭제(`@SQLRestriction`) 상속 |
| `login_id` | VARCHAR(50) | NOT NULL, UNIQUE(`uk_admin_account_login_id`) |
| `password` | VARCHAR(60) | NOT NULL — BCrypt 해시(고정 60자) |

- 리포지토리: `AdminAccountJpaRepository.findByLoginId(loginId: String): AdminAccount?`
- Flyway: `Vyyyy.MM.dd.HH.mm.ss__create_admin_account_table.sql`(생성 시각 timestamp 규칙) — 시드 없음(최초 계정은 운영자 수동 INSERT).
- 도메인 행위 없음(자격 증명 보관 전용) — 비밀번호 검증은 api 계층 `AdminLoginService` 가 BCrypt 로 수행.
- `ModuleBoundaryTest` 허용 맵에 `admin` 컨텍스트 등록(타 도메인 의존 0).

## 소비하는 기존 엔티티

### Food (`common.domain.food.model.Food`) — 읽기(집계) + 기존 서비스 경유 쓰기

| 사용 필드 | 용도 |
|-----------|------|
| `contentStatus: FoodContentStatus` | 대시보드 집계 축 — INCOMPLETE·PENDING_IMAGE·PENDING_REVIEW·READY |
| (전체 행) | 시드 등록·이미지 배치 제출은 기존 서비스 로직 재사용 — 이 기능이 직접 쓰기 규칙을 만들지 않음 |

- `BaseEntity.@SQLRestriction("status = 'ACTIVE'")` 로 소프트삭제 행은 집계에서 자동 제외.
- **리포지토리 변경**: `FoodJpaRepository` 에 상태별 집계 추가 —
  `fun countGroupByContentStatus(): List<FoodStatusCount>` (JPQL group-by, projection `status: FoodContentStatus, count: Long`).

### Member (`common.domain.member.model.Member`) — 읽기 전용

| 사용 필드 | 용도 |
|-----------|------|
| `id`, `createdAt` (BaseEntity) | 목록 정렬(id desc)·가입일 표시 |
| `provider`, `providerUid`, `email` | 상세 — 소셜 식별 정보 |
| `nickname`, `profile`(MemberProfile) | 목록·상세 — 프로필(회피 성분·맵기 선호·국가·프로필 이미지) |
| `memberStatus: MemberStatus` | 상태 뱃지 — ACTIVE·SUSPENDED |
| `onboardingCompleted`, `scanCount`, `reviewCount`, `ranking` | 상세 — 활동 지표 |

- 리포지토리 변경 없음 — `JpaRepository.findAll(Pageable)`·`findById` 사용.
- 탈퇴(DELETED) 회원은 `@SQLRestriction` 으로 목록·상세에서 자동 제외.

## 인증 상태 (비영속)

| 항목 | 값 |
|------|-----|
| 인증 상태 | ADMIN JWT(기존 `TokenIssuer`, id claim = `admin_account.id` — 회원 API 쪽은 리졸버 가드가 ADMIN 토큰 거절) → HttpOnly·Secure·SameSite=Strict·Path=/admin 세션 쿠키(Max-Age 미지정). 서버 상태 없음 |
| 확장 경로 | 계정 등록·비밀번호 변경 화면, 계정별 감사 로그 — 본 기능 범위 밖 |

## 뷰 모델 (api 계층 — `com.kbap.api.admin`, 영속 아님)

엔티티를 템플릿에 직접 노출하지 않는다(헌법 추가 제약).

| 뷰 모델 | 내용 |
|---------|------|
| `AdminFoodDashboardView` | `total: Long`, 상태별 건수 4종(0 채움 보장), `readyRatio`(0~100, 소수 1자리), 최근 제출 결과 flash |
| `AdminMemberSummaryView` | id·닉네임·이메일·provider·상태·가입일 — 목록 행 |
| `AdminMemberPageView` | `items: List<AdminMemberSummaryView>`, `page`·`totalPages`·`totalCount`·`hasPrev`·`hasNext` |
| `AdminMemberDetailView` | 식별 정보 + 프로필(회피 성분 코드·맵기·국가·프로필 이미지 URL) + 상태·활동 지표·가입일 |

## 상태 전이

이 기능이 만드는 상태 전이 없음 — 시드 등록·이미지 배치 제출의 전이(INCOMPLETE→…→READY, ImageBatch 흐름)는 기존 서비스가 소유하며 화면은 트리거·표시만 한다.
