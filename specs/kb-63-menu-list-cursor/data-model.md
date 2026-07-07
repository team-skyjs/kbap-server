# Phase 1 Data Model: 메뉴 목록 조회 (무한 스크롤, no-offset)

**신규 DB 스키마 없음** — 기존 `food`(+`food_avoidance_substance`) 테이블을 읽기만 한다. 정렬·커서 키가 PK `id` 라 추가 인덱스도 불필요. 아래는 코드 레벨 모델(도메인 port·유스케이스 DTO·web DTO)이다.

## 1. 도메인 (`:core:food`)

### FoodRepository (port 확장)

```kotlin
interface FoodRepository {
    fun findByKoreanName(name: String): Food?          // 기존

    // 신규: 최신순(id desc) keyset 페이지. cursor=null 이면 첫 페이지.
    // 반환 순서는 id 내림차순. size 개 이하로 반환(hasNext 판정은 어댑터가 size+1 조회로 처리하되,
    // 포트는 "요청 size 만큼"을 계약으로 둔다 — 아래 R 참고).
    fun findMenuPage(cursor: Long?, size: Int): List<Food>
}
```

- **계약**: `cursor` 가 주어지면 `id < cursor` 인 food 만, 없으면 전체에서 `id` 내림차순 상위 `size` 개.
- 소프트삭제 food 는 `@SQLRestriction("status='ACTIVE'")` 로 자동 제외(BaseEntity).
- `Food.overallRisk(avoidedCodes)`·`displayName(lang)`·`avoidanceSubstances`(위험도 계산 입력)는 기존 도메인 API 재사용 — 신규 도메인 로직 없음.

> **hasNext 판정 위치**: 어댑터가 내부적으로 `size+1` 을 조회해 21개면 hasNext=true·마지막 1개 버림. 포트 시그니처를 깨끗이 두기 위해 유스케이스가 `size+1` 을 요청하고 결과 개수로 hasNext 를 판정하는 방식도 가능(택1은 구현 단계 — quickstart 참고). 기본안: **유스케이스가 `size+1` 요청 → 개수로 hasNext 판정 → size 로 trim**. 포트는 순수 "id<cursor 상위 N" 만 책임.

## 2. 영속 (`:infra:persistence`)

### FoodJpaRepository (쿼리 추가)

```kotlin
// keyset id 조회 (컬렉션 미조인) — cursor=null 은 첫 페이지
@Query(
    """
    select f.id from FoodJpaEntity f
    where (:cursor is null or f.id < :cursor)
    order by f.id desc
    """,
)
fun findMenuPageIds(@Param("cursor") cursor: Long?, pageable: Pageable): List<Long>

// 기존 findByIdInWithAvoidanceSubstances 재사용하되 정렬만 desc 로 맞춘 변형이 필요하면 추가.
// (현행 findByIdInWithAvoidanceSubstances 는 order by f.id asc → 목록은 desc 정렬을 어댑터에서 보정하거나 desc 쿼리 추가)
@Query(
    """
    select distinct f from FoodJpaEntity f
    left join fetch f.foodAvoidanceSubstances
    where f.id in :ids
    order by f.id desc
    """,
)
fun findByIdInWithAvoidanceSubstancesDesc(@Param("ids") ids: List<Long>): List<FoodJpaEntity>
```

### FoodRepositoryAdapter (구현)

```kotlin
override fun findMenuPage(cursor: Long?, size: Int): List<Food> {
    val ids = foodJpaRepository.findMenuPageIds(cursor, PageRequest.of(0, size))
    if (ids.isEmpty()) return emptyList()
    return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
}
```

- 2단계 패턴(R2). 컬렉션 fetch-join + limit 인메모리 페이징 회피.
- 도메인은 JPA 미의존 — `toDomain()` 만 호출(원칙 IV).

## 3. 유스케이스 (`:application:client`)

### BrowseMenusInput / BrowseMenusResult (신규 DTO)

```kotlin
data class BrowseMenusInput(
    val cursor: Long?,     // null=첫 페이지
    val lang: String?,     // 언어 코드(원칙 V 폴백/거절)
)

data class BrowseMenusResult(
    val items: List<MenuSummaryView>,
    val nextCursor: Long?,   // hasNext=false 면 null
    val hasNext: Boolean,
) {
    data class MenuSummaryView(
        val foodId: Long,
        val name: String,               // 요청 언어 표시명(ko 폴백)
        val imageRef: String?,
        val spiciness: Int,             // 0~10
        val overallRiskStatus: RiskLevel,
    )
}
```

### BrowseMenusUseCase (신규)

책임(상세 유스케이스 구성요소 재사용):
1. `languageResolver.resolve(input.lang)` — 미지원 코드면 여기서 400 유발(원칙 V).
2. `foodRepository.findMenuPage(input.cursor, PAGE_SIZE + 1)` — 21개 조회.
3. `hasNext = rows.size > PAGE_SIZE`, `items = rows.take(PAGE_SIZE)`, `nextCursor = if (hasNext) items.last().id else null`.
4. 위험도: `avoidedCodes = avoidedSubstanceProvider.avoidedCodes()`(코드 Ref 변환) 1회 + `avoidanceSubstanceRepository.findByCodes(items 전체 성분코드 합집합)` 1회 → food 별 `overallRisk(avoidedCodes ∩ 카탈로그존재코드)`.
5. `MenuSummaryView(foodId=food.id!!, name=food.displayName(lang), imageRef, spiciness=food.spiciness.value, overallRiskStatus)`.

- `PAGE_SIZE = 20` 상수. `@Transactional(readOnly = true)`.
- 위험도 계산 로직은 상세와 동일 의미(R4) — 필요 시 공통 헬퍼로 추출(과설계 지양, 우선 인라인).

## 4. Web (`:app:api`)

### MenuSummaryResponse (공유 'food summary' — FR-008)

```kotlin
data class MenuSummaryResponse(
    val foodId: Long,
    val name: String,
    val imageRef: String?,
    val spiciness: Int,
    val overallRiskStatus: String,   // RiskLevel.name (SAFE/CAUTION/DANGER/UNKNOWN)
) {
    companion object { fun from(view: BrowseMenusResult.MenuSummaryView): MenuSummaryResponse ... }
}
```

### Page<T> (공유 커서 페이지 봉투 — `com.meogo.app.api.common`)

```kotlin
data class Page<T>(
    val items: List<T>,
    val hasNext: Boolean,
    val nextCursor: Long? = null,
)
```

- **표준 커서 페이지 봉투** — `BaseResponse`·`ApiPaths` 와 나란히 `app/api/common` 에 둔다. 모든 keyset 목록 API 가 재사용(향후 검색 포함). 전용 `MenuListResponse` 는 두지 않는다.
- `T` 에 항목 DTO 를 넣는다 — 본 API 는 **`Page<MenuSummaryResponse>`**.
- 리스트 필드명은 `BaseResponse.payload` 와의 중복(중첩 `payload.payload`)을 피해 **`items`** 로 둔다. `items` = 항목 리스트(≤20), `hasNext` = 다음 페이지 여부, `nextCursor` = **숫자(Long?)** 마지막 항목 foodId(`hasNext=false` 면 null).
- 최종 응답: `ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>>` =
  `BaseResponse.ok(Page(items = result.items.map(MenuSummaryResponse::from), hasNext = result.hasNext, nextCursor = result.nextCursor))`.
- 봉투 이중 중첩: `BaseResponse.payload` = `Page`, `Page.items` = 항목 리스트 → 응답 경로 `payload.items[]`.
- `MenuSummaryResponse` 는 향후 검색(별도 태스크)이 그대로 재사용하는 공유 스키마.

## 상태 전이

없음(읽기 전용 조회). food 의 소프트삭제 상태만 조회 필터(ACTIVE)로 반영.

## 검증 규칙 요약

| 규칙 | 위치 | 결과 |
|------|------|------|
| cursor 파싱 불가/음수 | 컨트롤러 `require` 또는 usecase | 400 fail |
| 미지원 lang 코드 | `LanguageResolver`(원칙 V) | 400 + 지원목록 |
| 빈 결과 | usecase 정상 흐름 | 200 `items:[] hasNext:false nextCursor:null` |
| 마지막 페이지 | `rows.size ≤ 20` | `hasNext:false nextCursor:null` |
| 페이지 크기 | 상수 20 | 요청이 크기 미지정 |
