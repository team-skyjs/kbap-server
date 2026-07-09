# Phase 1 Data Model: 검색어에 맞는 메뉴 조회 (다국어 부분 일치, no-offset)

**신규 DB 스키마 없음** — 기존 `food`(+`food_avoidance_substance`) 테이블을 읽기만 한다. 검색 대상은 컬럼 `korean_name` + JSON 컬럼 `name_translations`. leading-wildcard LIKE 라 추가 인덱스도 없음(research R3). 아래는 코드 레벨 모델이다. **응답 DTO·페이지 봉투·언어/커서/회피 컴포넌트는 KB-63 것을 그대로 재사용**하며 여기 재정의하지 않는다.

## 1. 도메인 (`:core:food`)

### FoodRepository (port 확장)

```kotlin
interface FoodRepository {
    fun findById(id: Long): Food?
    fun findMenuPage(cursor: Long?, size: Int): List<Food>            // 기존(KB-63)

    // 신규: 키워드가 korean_name 또는 lang 번역명에 포함되는 food 를 id 내림차순 keyset 페이지로.
    // lang == KO 이면 korean_name 만, 그 외면 korean_name OR 해당 언어 번역명 매칭.
    fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food>
}
```

- **계약**: `cursor` 가 주어지면 `id < cursor` 인 것만, 없으면 전체에서 매칭 상위 `size` 개(id 내림차순). 매칭은 대소문자 비구분 부분 일치.
- `lang` 은 **kernel 타입**(`com.meogo.core.kernel.lang.LanguageCode`)이라 도메인 port 가 받아도 ORM/Spring-free 유지(원칙 IV). JSON path 조립·SQL 은 어댑터 책임.
- 소프트삭제 제외는 어댑터 네이티브 쿼리의 `status='ACTIVE'` 로 보장(research R4).
- `keyword` 는 **trim·비공백 보장된 값**만 전달된다(검증은 유스케이스, 아래 3).

### FoodErrorCode (추가)

```kotlin
enum class FoodErrorCode(...) : ErrorCode {
    NOT_FOUND(400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR(400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD(400, "검색어를 입력해 주세요"),   // 신규(FR-011)
}
```

> 메시지는 정중한 종결형(`~요/습니다`). 최종 문구는 구현 시 확정.

## 2. 영속 (`:infra:persistence`)

### FoodJpaRepository (네이티브 검색 쿼리 추가)

```kotlin
@Query(
    nativeQuery = true,
    value = """
        select f.id from food f
        where f.status = 'ACTIVE'
          and (:cursor is null or f.id < :cursor)
          and (
            f.korean_name like concat('%', :kw, '%')
            or (:jsonPath is not null
                and json_unquote(json_extract(f.name_translations, :jsonPath)) like concat('%', :kw, '%'))
          )
        order by f.id desc
        limit :size
    """,
)
fun searchMenuPageIds(
    @Param("kw") keyword: String,
    @Param("jsonPath") jsonPath: String?,
    @Param("cursor") cursor: Long?,
    @Param("size") size: Int,
): List<Long>
```

- **네이티브**라 `@SQLRestriction` 미적용 → `status = 'ACTIVE'` 명시(research R4).
- `:jsonPath` = `null`(KO) 또는 `$."<langCode>"`. `_ci` 콜레이션으로 LIKE 대소문자 비구분(research R2).
- 정렬·개수는 네이티브 `order by f.id desc limit :size` 로 확정(JPQL `Pageable` 대신).
- 2단계 로드는 **기존 `findByIdInWithAvoidanceSubstancesDesc(ids)` 재사용**(신규 없음).

### FoodRepositoryAdapter (구현)

```kotlin
override fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
    val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
    val ids = foodJpaRepository.searchMenuPageIds(keyword, jsonPath, cursor, size)
    if (ids.isEmpty()) return emptyList()
    return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
}
```

- 도메인은 JPA/SQL 미의존 — `toDomain()` 만 호출(원칙 IV).

## 3. 유스케이스 (`:application:client`)

### SearchMenusInput (신규 DTO) / 결과 재사용

```kotlin
data class SearchMenusInput(
    val keyword: String?,   // 원문(미검증) — resolver 가 trim·blank 검사
    val cursor: Long?,
    val lang: String?,
)
```

- **결과는 `BrowseMenusResult`(+`MenuSummaryView`) 재사용** — items·nextCursor·hasNext 구조가 동일(FR-009). 신규 결과 DTO 두지 않음.

### SearchKeywordResolver (신규 — resolveCursor 형제)

```kotlin
fun resolveKeyword(keyword: String?): String {
    val trimmed = keyword?.trim().orEmpty()
    if (trimmed.isEmpty()) throw FoodException(FoodErrorCode.BLANK_SEARCH_KEYWORD)
    return trimmed
}
```

### SearchMenusUseCase (신규)

책임(browse 와 페이지 소스만 다름):
1. `keyword = resolveKeyword(input.keyword)` — 공백이면 400(FR-011).
2. `lang = languageResolver.resolve(input.lang)` — 미지원 코드면 400(원칙 V).
3. `rows = foodRepository.searchMenuPage(keyword, lang, input.cursor, PAGE_SIZE + 1)` — 21개.
4. `hasNext = rows.size > PAGE_SIZE`, `items = rows.take(PAGE_SIZE)`, `nextCursor = if (hasNext) items.last().id else null`.
5. 위험도·뷰 조립: **browse 와 동일** — 회피 조달 1회 + 카탈로그 일괄 1회 → food별 `overallRisk(avoided ∩ catalog)` → `MenuSummaryView`(foodId·displayName(lang)·koreanName·imageRef·spiciness·risk).
6. `BrowseMenusResult(items = views, nextCursor, hasNext)`.

- `PAGE_SIZE = 20`. `@Transactional(readOnly = true)`.

### MenuSummaryAssembler (신규·권장 — browse/search 공유)

```kotlin
@Component
class MenuSummaryAssembler(
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
) {
    fun assemble(foods: List<Food>, lang: LanguageCode): List<BrowseMenusResult.MenuSummaryView> { ... }
}
```

- 4·5 단계의 위험도·뷰 조립(현 `BrowseMenusUseCase` 의 `catalogCodes`+views 매핑)을 추출. `BrowseMenusUseCase`·`SearchMenusUseCase` 가 공유. **안전 직결 위험도 로직의 단일 출처**(research R7). 추출은 Refactor 단계 허용(우선 인라인 후 정리 가능).

## 4. Web (`:app:api`) — 전부 재사용 + 컨트롤러/스웨거만 신규

- **재사용**: `Page<T>`(`common`), `MenuSummaryResponse`(food summary, FR-009), `BaseResponse`, `ApiPaths.V1`, `resolveCursor`.
- **신규**: `MenuSearchController`(+`MenuSearchApi`).

```kotlin
@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class MenuSearchController(private val searchMenusUseCase: SearchMenusUseCase) : MenuSearchApi {
    override fun search(keyword: String, cursor: String?, lang: String?):
        ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>> {
        val result = searchMenusUseCase.search(
            SearchMenusInput(keyword = keyword, cursor = resolveCursor(cursor), lang = lang),
        )
        return ResponseEntity.ok(
            BaseResponse.ok(Page(
                items = result.items.map(MenuSummaryResponse::from),
                hasNext = result.hasNext,
                nextCursor = result.nextCursor,
            )),
        )
    }
}
```

- 경로 `GET /api/v1/foods/search`(목록 `GET /api/v1/foods` 와 충돌 회피). `keyword` **required**, `cursor`·`lang` optional.
- 응답 경로: `payload.items[]`(각 항목 `MenuSummaryResponse`)·`payload.hasNext`·`payload.nextCursor`.

## 상태 전이

없음(읽기 전용 조회). food 소프트삭제 상태만 조회 필터(네이티브 `status='ACTIVE'`)로 반영.

## 검증 규칙 요약

| 규칙 | 위치 | 결과 |
|------|------|------|
| 빈/공백 검색어 | `resolveKeyword`(usecase) | 400 fail (`BLANK_SEARCH_KEYWORD`) |
| cursor 파싱 불가/음수 | `resolveCursor`(재사용) | 400 fail (`INVALID_CURSOR`) |
| 미지원 lang 코드 | `LanguageResolver`(원칙 V) | 400 + 지원목록 |
| 소프트삭제 food | 네이티브 `status='ACTIVE'` | 결과 제외 |
| 결과 없음 | usecase 정상 흐름 | 200 `items:[] hasNext:false nextCursor:null` |
| 마지막 페이지 | `rows.size ≤ 20` | `hasNext:false nextCursor:null` |
| 매칭 대상 | KO→korean_name / else→korean_name OR json(lang) | LIKE 부분 일치, 대소문자 비구분 |
| 페이지 크기 | 상수 20 | 요청이 크기 미지정 |
