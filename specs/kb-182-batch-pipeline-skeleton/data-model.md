# Data Model: 배치 콘텐츠 파이프라인 골격 (KB-182)

**신규 테이블·Flyway 마이그레이션 없음.** 기존 스키마(`food`·`food_avoidance_substance`)를 그대로 쓴다.

## 엔티티 (기존 — 변경 사항만 표시)

### Food (`food` 테이블, `:domain:food`)

| 필드 | 타입 | READY 완비 판정 관여 |
|------|------|---------------------|
| `koreanName` | String (unique) | — (INCOMPLETE 생성 시점부터 존재) |
| `imageRef` | String? | ✅ 비-null·비-blank |
| `description` | String | ✅ 비-blank 이고 `PLACEHOLDER_DESCRIPTION`("설명 준비 중") 아님 |
| `nameTranslations` | Map<String,String> (JSON) | ✅ 9개 대상 언어 코드 전부 포함 |
| `descriptionTranslations` | Map<String,String> (JSON) | ✅ 9개 대상 언어 코드 전부 포함 |
| `spiciness` | Int | ❌ 게이트 제외 — 기본 0 이 유효값(KB-209 에서 채움) |
| `contentStatus` | `FoodContentStatus` (INCOMPLETE/READY) | 전이 대상 |
| `avoidanceSubstances` | 읽기 전용 EAGER 연관 | ❌ 판정에 직접 사용 안 함(D3 — 스냅샷 불일치) |

9개 대상 언어 = `LanguageCode` 중 `KO` 제외 전부: `zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`.

**신규 도메인 메서드**:

```kotlin
fun transitionToReadyIfComplete(hasAvoidanceMapping: Boolean): Boolean
```

- 이미 READY → 상태 불변, true 반환 (멱등)
- 위 4조건(imageRef·description·번역 2종 완비 + `hasAvoidanceMapping`) 전부 만족 → `contentStatus = READY`, true
- 하나라도 미달 → 상태 불변, false

### FoodAvoidanceSubstance (`food_avoidance_substance` 테이블) — 변경 없음

존재 여부만 READY 게이트의 입력(boolean)이 된다. 쓰기는 KB-209 범위.

## 상태 전이

```text
INCOMPLETE ──(4작업 완비 && 전이 시도)──▶ READY
INCOMPLETE ──(미완비 전이 시도)──▶ INCOMPLETE (불변)
READY ──(재시도)──▶ READY (멱등, 오류 없음)
```

READY → INCOMPLETE 역전이는 없다(범위 밖).

## 배치 창구 계약 (`:domain:food`)

```kotlin
@Service
class FoodContentBatchService internal constructor(
    private val foodRepository: FoodJpaRepository,
) {
    @Transactional(readOnly = true)
    fun getIncompleteFoods(afterId: Long?, size: Int): List<Food>
    // content_status='INCOMPLETE' and id > :afterId(null 이면 처음부터) order by id asc limit :size

    @Transactional
    fun completeContent(food: Food, hasAvoidanceMapping: Boolean): Boolean
    // 스텝이 채운 필드 저장(save) + transitionToReadyIfComplete 시도, 전이 여부 반환
}
```

`FoodJpaRepository` 에 키셋 조회 쿼리 추가(`internal` 유지). `FoodScoringSource` 는 삭제.

## 잡 구조 (`:app:batch`) — 단일 클래스, 작업별 메서드

```kotlin
class FoodContentJob(...) {
    fun run()                                    // 청크 소진 루프 + 음식 1건 try/catch 격리
    private fun process(food: Food)              // 4작업 순차 호출 → completeContent
    private fun generateImage(food: Food)        // KB-184 가 본문 구현 (지금은 비어 있음)
    private fun generateDescription(food: Food)  // KB-183
    private fun translateNames(food: Food)       // KB-183
    private fun mapAvoidance(food: Food): Boolean // KB-209 — 매핑 쓰기 후 true 반환
}
```

- 스텝 인터페이스·플러그인 빈 없음 — 후속 태스크는 해당 메서드 본문에 LLM 호출을 채운다(작업별 호출 구분은 메서드 경계로 표현).
- 메서드 안 예외 = 해당 음식 실패 → 건 단위 격리(INCOMPLETE 잔류, 다음 음식 계속).

## 설정

| 키 | 기본값 | 의미 |
|----|--------|------|
| `kbap.batch.content.chunk-size` | 10 | 청크당 INCOMPLETE 조회 건수 |
| `kbap.batch.content.runner.enabled` | false | 부팅 시 콘텐츠 잡 실행 여부 |
