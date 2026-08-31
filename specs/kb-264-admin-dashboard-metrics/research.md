# Research: 관리자 대시보드 확장 (kb-264)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 구현 방식 선택지를 결정으로 고정한다.

## R1. 신규 대시보드 페이지 vs 기존 적재 현황 페이지 확장

- **Decision**: 기존 `/admin/foods`(적재 현황) 페이지를 확장한다. 신규 URL·내비게이션 항목을 만들지 않는다.
- **Rationale**: `/admin` 이 이미 `/admin/foods` 로 리다이렉트해 사실상 홈 대시보드다. Jira 태스크도 "현재 음식 적재 정보 **외에** ... 추가"로 기존 화면 확장을 명시한다. 페이지 추가는 컨트롤러·템플릿·내비 3곳을 늘리는 비용 대비 이득이 없다.
- **Alternatives considered**: 신규 `/admin/dashboard` 페이지 + 홈 리다이렉트 변경 — 화면 분리 이득이 없고 조각만 늘어 기각.

## R2. 그래프 렌더링 방식

- **Decision**: 차트 라이브러리 없이 **순수 CSS 바 차트**(막대 높이 = 값/최댓값 비율, Thymeleaf 인라인 style)로 그린다. 기존 admin.css 의 `.progress`/`.progress-fill` 패턴을 세로 막대로 확장한다.
- **Rationale**: 데이터가 7포인트 × 3그래프뿐이다. 기존 관리자 화면은 JS 없이 서버렌더 + CSS 로만 구성돼 있고, 요구는 "그래프 시각화 정도"(추이 파악)라 축·툴팁·줌이 불필요하다. 외부 JS(CDN) 의존을 새로 들이지 않는 게 가장 작은 변경이다.
- **Alternatives considered**:
  - Chart.js(CDN) — 신규 외부 의존 + JS 초기화 코드. 7포인트 막대에 과함. 기각.
  - 인라인 SVG — CSS 바보다 마크업 생성이 복잡(좌표 계산). 이득 없음. 기각.

## R3. 일자별 집계 쿼리의 위치·형태

- **Decision**: 각 소유 도메인 리포지토리에 **JPQL `function('date', e.createdAt)` group-by 프로젝션 쿼리**를 추가하고, 누락 날짜 0-fill 은 `AdminDashboardMetricsService` 가 Kotlin 으로 수행한다.
  - `ScanHistoryJpaRepository`: 스캔 횟수 count — `createdAt >= :from`
  - `FoodJpaRepository`: 신규 음식 count — `createdAt >= :from`
  - `LlmCallCostJpaRepository`: `sum(costUsd)` — `createdAt >= :from`
  - `MemberJpaRepository`: `countByMemberStatus(MemberStatus.ACTIVE)` 파생 쿼리
- **Rationale**: JPQL 엔티티 쿼리는 `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 를 자동 적용받아 소프트삭제 제외 조건을 중복 기술하지 않는다(네이티브 쿼리는 수동 status 조건이 필요해 실수 여지). 리포지토리가 쿼리를 소유하고 소비 계층이 직접 쓰는 구조는 원칙 IV·KB-220 그대로다. member 는 도메인 상태 컬럼(`member_status`)이 별도라 파생 쿼리 카운트가 가장 짧다.
- **Alternatives considered**:
  - 네이티브 SQL `DATE(created_at)` — 동작 동일하나 `status='ACTIVE'` 수동 반복 필요. 기각.
  - 서비스에서 7일치 엔티티 전체 로드 후 Kotlin group-by — 데이터가 커지면 낭비, DB group-by 가 동일 난이도. 기각.
  - 사전 집계 테이블/배치 — 스펙 Assumption 이 조회 시점 계산으로 충분하다고 고정. 기각.

## R4. LLM 비용 통화

- **Decision**: `cost_usd` 합계(USD)를 표시한다. 소수 표시 형식은 `$0.000000` 단위 절삭 없이 소수 유지(spec edge case — 1달러 미만도 뭉개지 않음).
- **Rationale**: LLM 단가의 원천 통화가 USD 라 정밀도 손실이 없고, 스펙 엣지 케이스도 달러 기준으로 서술됐다. `cost_krw` 는 환산값이라 이중 표시 가치가 낮다.
- **Alternatives considered**: KRW 표시 — 환산 시점 환율에 묶인 파생값. 필요해지면 툴팁/보조 표기로 추가. 기각.

## R5. 날짜 기준·시간대

- **Decision**: `created_at`(LocalDateTime, 서버·DB 기본 시간대 = KST 운영 전제)을 `date()` 로 자른 일자 기준, 오늘 포함 최근 7일(`from = today - 6일 00:00`). 요일 라벨은 서비스가 한국어(월·화·…·일)로 내려준다.
- **Rationale**: 전 엔티티가 `BaseEntity.createdAt` 을 공유하고 별도 타임존 변환 계층이 없다 — 기존 코드와 동일한 전제를 따르는 것이 일관적이다. 스펙 Assumption(KST) 과 일치.
- **Alternatives considered**: UTC 저장/변환 도입 — 이 기능 범위를 넘는 인프라 변경. 기각.

## R6. 인덱스 추가 여부

- **Decision**: 신규 인덱스를 추가하지 않는다.
- **Rationale**: `llm_call_cost` 는 이미 `idx_llm_call_cost_created_at` 보유. `scan_history`(`(member_id, created_at)` 복합만 보유)·`food` 의 7일 range group-by 는 관리자 저빈도 조회라 풀스캔이어도 수용 범위다(동시성·성능 최소 방어 원칙). 느려지면 그때 `created_at` 단독 인덱스를 마이그레이션으로 추가한다.
- **Alternatives considered**: 선제 인덱스 마이그레이션 — 측정 없는 선제 최적화. 기각.
