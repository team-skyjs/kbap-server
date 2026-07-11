# Data Model: 홈 화면 조회 (KB-111)

## 신규 도메인 — `:core:scan`

### ScanHistory (Aggregate Root)

회원이 특정 음식을 스캔한 이력 1건. 최근 스캔 섹션의 원천.

| 필드 | 타입 | 규칙 |
|------|------|------|
| `id` | `Long?` | 영속 시 부여(신규 null) |
| `memberId` | `Long` | 스캔한 회원. 비회원 스캔은 기록하지 않음 |
| `foodId` | `Long` | 매칭된 음식. 매칭 실패·비-READY 는 기록 안 함 |
| `scannedAt` | (영속 `created_at` 재사용) | 스캔 시각 = 엔티티 생성 시각 |

- 팩토리: `ScanHistory.record(memberId: Long, foodId: Long): ScanHistory` (id=null).
- 불변 — 상태 변경 메서드 없음(append-only 로그).

### ScanHistoryRepository (port)

```kotlin
interface ScanHistoryRepository {
    fun saveAll(records: List<ScanHistory>)
    fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long>
}
```

- `findRecentReadyFoodIds`: 같은 food 는 최신 1건으로 dedup, 스캔 시각 내림차순, READY(+ACTIVE) 음식만, `limit` 개. (SQL 은 research R3.)

## 영속 — `:infra:persistence` (신규 `scan_history`)

### 테이블 `scan_history`

```sql
CREATE TABLE scan_history (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    member_id  BIGINT      NOT NULL,
    food_id    BIGINT      NOT NULL,
    status     VARCHAR(20) NOT NULL,               -- EntityStatus: ACTIVE/DELETED
    created_at DATETIME(6) NOT NULL,               -- 스캔 시각으로 사용
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_scan_history_recent (member_id, created_at)
);
```

- FK 는 두지 않는다(컨텍스트 격리 — member·food 는 ID 값 참조, 헌법 II). 조회 시 `JOIN food` 로 READY 필터만.
- `idx_scan_history_recent(member_id, created_at)` — 회원별 최근 조회 정렬 커버.
- `ScanHistoryJpaEntity` 는 `BaseEntity` 상속(id·status·created_at·updated_at 공통, `@SQLRestriction status='ACTIVE'`). 별도 `scanned_at` 컬럼 없음.
- `toDomain()` / `from(domain)` 엔티티 내 위치, adapter 는 이만 호출.

## 수정 — `:core:food`

`FoodRepository` port 에 2개 추가:

```kotlin
fun findRandomReady(size: Int): List<Food>            // 인기 음식 임시(무작위)
fun findAllReadyByIds(ids: List<Long>): List<Food>    // 최근 스캔 food 일괄 로드(READY만)
```

- `findAllReadyByIds` 반환은 순서 무보장 → 홈 유스케이스가 `findRecentReadyFoodIds` 의 순서대로 재정렬.
- 영속: `FoodJpaRepository.findRandomReadyIds(size)` 네이티브(`ORDER BY RAND()`) + 기존 `findByIdInWithAvoidanceSubstances` 재사용.

## 수정 — 기피 성분 프로바이더 (application:client)

```kotlin
interface AvoidedSubstanceProvider {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>   // 무인자 → memberId 추가
}
```

- `MemberAvoidedSubstanceProvider`(신규, `MockAvoidedSubstanceProvider` 삭제): `memberId==null` → `emptySet`; 회원 → `MemberRepository.findById` 프로필 코드 → enum. 미존재·미설정 → `emptySet`.

## 응용 DTO — 홈 (application:client/home/dto)

### HomeResult

```kotlin
data class HomeResult(
    val avoidedSubstances: List<AvoidedSubstanceView>?,   // 비회원 null, 회원 없음 []
    val popularFoods: List<FoodSummaryView>,              // 항상(최대 5)
    val recentScans: List<FoodSummaryView>?,              // 비회원 null, 회원 없음 []
)
```

### AvoidedSubstanceView

```kotlin
data class AvoidedSubstanceView(
    val code: String,      // AvoidanceSubstanceCode.name
    val name: String,      // 회원 appLanguage 지역화(번역 부재 시 ko 폴백)
)
```

- `FoodSummaryView`(기존 `food/dto`) 재사용 — `foodId·name·koreanName·imageRef·spiciness·overallRiskStatus`. 위험도는 회원 기피 코드 기준(비회원 empty → UNKNOWN).

## 상태 전이

없음(홈은 순수 조회). `ScanHistory` 는 append-only(전이 없음). 스캔 기록은 기존 스캔 플로우의 부수 write.

## 검증 규칙 (요구사항 매핑)

| 규칙 | 출처 | 위치 |
|------|------|------|
| 비회원 = 개인화 null, 인기음식만 | FR-003 | HomeQueryUseCase (memberId null 분기) |
| 무효/만료 토큰 = 401 | FR-003 | AuthMemberIdOrNullArgumentResolver |
| 회원 기피 = 프로필 실제 코드(없으면 []) | FR-004 | MemberAvoidedSubstanceProvider + HomeQueryUseCase |
| 최근 스캔 dedup·시각 내림차순·10개·READY만 | FR-008/009 | ScanHistoryRepository.findRecentReadyFoodIds (SQL) |
| 스캔 시 매칭 READY 음식 회원별 기록 | FR-007 | ScanUseCase (memberId!=null 조건) |
| 인기음식 무작위 5·계약 고정 | FR-006 | FoodRepository.findRandomReady + FoodSummaryView |
| 언어: 회원 appLanguage / 비회원·미완료 en | FR-002 | HomeQueryUseCase |
