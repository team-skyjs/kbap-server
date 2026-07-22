# Data Model: 배치 콘텐츠 파이프라인 골격 (KB-182)

**food 컬럼 변경 없음.** 신규 마이그레이션은 Spring Batch 메타데이터(`BATCH_*`)뿐. 기피성분 매핑은 develop #82 이후 `food.avoidance_substances` JSON 컬럼(`List<FoodAvoidanceItem>`)이다 — 별도 `food_avoidance_substance` 테이블에 의존하지 않는다.

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
| `avoidanceSubstances` | `List<FoodAvoidanceItem>` (JSON 컬럼, #82) | ✅ 존재 여부가 `hasAvoidanceMapping` (KB-209 채움) |

9개 대상 언어 = `LanguageCode` 중 `KO` 제외 전부: `zh-Hans`·`en`·`ja`·`zh-Hant`·`vi`·`id`·`th`·`ru`·`es`.

**신규 도메인 메서드**:

```kotlin
fun needsImage(): Boolean                    // imageRef 비었으면 true
fun needsDescription(): Boolean              // description 이 blank 또는 placeholder 면 true
fun needsNameTranslations(): Boolean         // 9개 대상 언어 미완비면 true
fun needsDescriptionTranslations(): Boolean  // 9개 대상 언어 미완비면 true
fun needsAvoidanceMapping(): Boolean         // avoidanceSubstances(JSON) 비었으면 true
fun transitionToReadyIfComplete(): Boolean
```

- `needsX()` — 배치 processor 가 이미 된 작업의 LLM 호출을 건너뛰는(skip-if-done) 근거. 5작업 모두 대칭.
- `transitionToReadyIfComplete`: 이미 READY → 상태 불변·true(멱등). 5작업(`!needsImage && !needsDescription && !needsNameTranslations && !needsDescriptionTranslations && !needsAvoidanceMapping`) 전부 만족 → `contentStatus = READY`·true. 하나라도 미달 → 상태 불변·false. **파라미터 없음** — 기피성분이 food 행 JSON 컬럼이라 엔티티 자기 상태로 완결(구 `hasAvoidanceMapping` 파라미터 제거, #82 이후).

### 기피성분 매핑 — `food.avoidance_substances` JSON (#82)

`List<FoodAvoidanceItem>`(food 엔티티의 JSON 컬럼). 비어있지 않음 = 매핑 존재. develop #82 이 별도 테이블에서 JSON 컬럼으로 이관 — food 스냅샷에 함께 실려 오므로 구 D3 의 "EAGER 연관 스냅샷 불일치" 우려가 사라졌고, 전이 메서드가 `needsAvoidanceMapping()`(= `avoidanceSubstances.isEmpty()`)으로 자기 상태를 직접 본다(구 `hasAvoidanceMapping` 파라미터 제거). 쓰기(3-API 종합)는 KB-209 범위.

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

    @Transactional(propagation = REQUIRES_NEW)
    fun saveProgress(food: Food)
    // 한 작업 결과를 독립 트랜잭션으로 즉시 커밋 — 뒤 작업 실패해도 롤백 안 됨(작업별 재시도 근거)

    @Transactional
    fun completeContent(food: Food): Boolean
    // transitionToReadyIfComplete 시도 + save, 전이 여부 반환
}
```

`FoodJpaRepository` 에 키셋 조회 쿼리 추가(`internal` 유지). `FoodScoringSource` 는 삭제.

## 잡 구조 (`:app:batch`) — 평범한 순차 루프 (Spring Batch 미사용)

```text
FoodContentJob.run()   (ContentJobConfig 의 gated ApplicationRunner 가 실행)
 └─ while: getIncompleteFoods(afterId, chunkSize) 키셋 청크  (빈 청크면 종료)
     └─ for each food:  try { process(food) } catch { 로그 후 다음 음식 }   ← 건 단위 격리
          process(food) — 4작업 skip-if-done 순차:
            if needsImage()               → generateImage → saveProgress(즉시 커밋)
            if needsDescription()          → generateDescription → saveProgress
            if needs(Name/Desc)Translations() → translateContent → saveProgress
            if needsAvoidanceMapping()     → mapAvoidance → saveProgress  (KB-209: API 3개 종합)
          → completeContent(food)          5작업 완비 시 READY 전이
     afterId = chunk.last().id
```

- **평범한 for 루프** — Spring Batch·Step·메타 테이블 없음. 잡 하나·순차 처리엔 이게 가장 단순(멘토 조언). 병렬화는 이후 스레드풀/future/코루틴으로.
- **작업별 skip-if-done** — 각 작업은 `food.needsX()` 로 이미 된 작업의 LLM 호출을 건너뛴다(해야 하는 음식만 LLM).
- **작업별 독립 커밋** — `saveProgress`(REQUIRES_NEW)로 한 작업 결과를 즉시 커밋. 뒤 작업이 실패해도 앞 작업이 롤백 안 돼 다음 실행에서 실패한 작업만 재시도.
- **건 단위 격리** — 음식 1건 처리 실패는 try/catch 로 그 건만 건너뛰고(INCOMPLETE 잔류·다음 실행 재시도) 잡은 계속. `afterId` 가 전진해 같은 실행에서 재조회 안 됨(무한 루프 없음).
- 4작업 메서드 본문은 후속(KB-183·184·209)이 채운다 — 스텝 인터페이스·플러그인 빈 없음.
- **재시작/재실행**: 상태가 `content_status`·작업별 필드에 있어 다음 실행이 미완만 재처리(프레임워크 메타 테이블 불필요).

## 설정

| 키 | 기본값 | 의미 |
|----|--------|------|
| `kbap.batch.content.chunk-size` | 10 | 한 번에 DB 에서 읽어 순차 처리할 INCOMPLETE 건수 |
| `kbap.batch.content.runner.enabled` | false | 부팅 시 콘텐츠 잡 실행 여부(실행 시 `--kbap.batch.content.runner.enabled=true`) |
