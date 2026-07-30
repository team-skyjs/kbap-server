# Data Model: 관리자 대시보드 확장 (kb-264)

**스키마 변경 없음** — 신규 테이블·컬럼·Flyway 마이그레이션이 없다. 기존 원천 테이블에 읽기 집계만 추가한다.

## 원천 엔티티 (기존, 변경 없음)

| 엔티티 | 테이블 | 사용 컬럼 | 지표 |
|--------|--------|-----------|------|
| `Member` | `member` | `member_status` | 총 가입자 수 (`member_status = ACTIVE`) |
| `ScanHistory` | `scan_history` | `created_at`, `status` | 최근 7일 일자별 스캔 횟수 |
| `Food` | `food` | `created_at`, `status` | 최근 7일 일자별 신규 등록 개수 |
| `LlmCallCost` | `llm_call_cost` | `created_at`, `cost_usd`, `status` | 최근 7일 일자별 비용 합계(USD) |

- 소프트삭제 제외(`status = 'ACTIVE'`)는 `BaseEntity` 의 `@SQLRestriction` 이 JPQL 에 자동 적용 — 쿼리에 중복 기술하지 않는다.
- `Member` 는 공통 `status` 외 도메인 상태 `member_status` 를 별도 보유 — 가입자 카운트는 `member_status` 기준.

## 리포지토리 집계 프로젝션 (신규 read 쿼리)

```text
MemberJpaRepository      +  countByMemberStatus(status: MemberStatus): Long
ScanHistoryJpaRepository +  countDailySince(from: LocalDateTime): List<DailyCount>      # date(createdAt), count(*)
FoodJpaRepository        +  countDailyCreatedSince(from: LocalDateTime): List<DailyCount>
LlmCallCostJpaRepository +  sumDailyCostUsdSince(from: LocalDateTime): List<DailyCostSum> # date(createdAt), sum(costUsd)
```

- 프로젝션 반환은 인터페이스 프로젝션 또는 생성자 프로젝션(date: LocalDate, value) — 기존 `FoodStatusCount` 선례를 따른다.
- `from` = 조회일 - 6일의 00:00 (오늘 포함 7일).

## 뷰 모델 (api admin 패키지, 신규)

```text
AdminDashboardMetricsView(
    totalActiveMembers: Long,
    weeklyScans: List<DailyMetricView>,       # 7건 고정, 과거→오늘 순
    weeklyNewFoods: List<DailyMetricView>,    # 7건 고정
    weeklyLlmCostUsd: List<DailyCostView>,    # 7건 고정
)
DailyMetricView(date: LocalDate, dayLabel: String, count: Long)      # dayLabel: 월…일
DailyCostView(date: LocalDate, dayLabel: String, costUsd: BigDecimal)
```

### 불변 규칙 (서비스가 보장)

- 각 주간 리스트는 **항상 7원소** — 집계에 없는 날짜는 0 으로 채운다(FR-006).
- 날짜는 과거 → 오늘 오름차순, `dayLabel` 은 해당 날짜의 실제 요일.
- 값은 음수 불가(집계 특성상 자연 보장).

## 상태 전이

없음 — 읽기 전용 기능.
