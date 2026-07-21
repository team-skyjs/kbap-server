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
fun transitionToReadyIfComplete(hasAvoidanceMapping: Boolean): Boolean
```

- `needsX()` — 배치 processor 가 이미 된 작업의 LLM 호출을 건너뛰는(skip-if-done) 근거.
- `transitionToReadyIfComplete`: 이미 READY → 상태 불변·true(멱등). 4작업(`!needsImage && !needsDescription && !needsNameTranslations && !needsDescriptionTranslations`) + `hasAvoidanceMapping` 전부 만족 → `contentStatus = READY`·true. 하나라도 미달 → 상태 불변·false.

### 기피성분 매핑 — `food.avoidance_substances` JSON (#82)

`List<FoodAvoidanceItem>`(food 엔티티의 JSON 컬럼). 비어있지 않음 = `hasAvoidanceMapping`. develop #82 이 별도 테이블에서 JSON 컬럼으로 이관 — food 스냅샷에 함께 실려 오므로 구 D3 의 "EAGER 연관 스냅샷 불일치" 우려는 사라졌다(전이 메서드는 `hasAvoidanceMapping` 파라미터 seam 유지). 쓰기는 KB-209 범위.

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
    fun completeContent(food: Food, hasAvoidanceMapping: Boolean): Boolean
    // transitionToReadyIfComplete 시도 + save, 전이 여부 반환
}
```

`FoodJpaRepository` 에 키셋 조회 쿼리 추가(`internal` 유지). `FoodScoringSource` 는 삭제.

## 잡 구조 (`:app:batch`) — Spring Batch chunk-oriented Step

```text
Job "foodContentJob" (RunIdIncrementer — 실행마다 새 인스턴스, 야간 반복)
 └─ Step "foodContentStep" (chunk = chunk-size(10), faultTolerant.skip)
     ├─ Reader   IncompleteFoodItemReader   getIncompleteFoods(lastReadId, pageSize) 키셋
     ├─ Processor FoodContentItemProcessor   음식 1건 4작업(skip-if-done) → ProcessedFood
     │              if needsImage()               → generateImage → saveProgress(즉시 커밋)
     │              if needsDescription()          → generateDescription → saveProgress
     │              if needs(Name/Desc)Translations() → translateContent → saveProgress
     │              mapAvoidance → Boolean         (KB-209: 매핑 있으면 skip, 없으면 API 3개 종합)
     └─ Writer    completeContent(food, hasAvoidanceMapping)  4작업 완비 시 READY 전이
```

- **작업별 skip-if-done** — 각 작업은 `food.needsX()` 로 이미 된 작업의 LLM 호출을 건너뛴다(해야 하는 음식만 LLM).
- **작업별 독립 커밋** — `saveProgress`(REQUIRES_NEW)로 한 작업 결과를 즉시 커밋. 뒤 작업이 실패해도 앞 작업이 롤백 안 돼 다음 실행에서 실패한 작업만 재시도. 청크가 커도(10) 재스캔 시 LLM 중복 없음.
- **faultTolerant().skip(Exception).skipLimit(MAX)** + SkipListener 로그 → 한 음식 실패는 그 건만 건너뜀(INCOMPLETE 잔류), 잡 계속.
- Processor 의 4작업 메서드 본문을 후속(KB-183·184·209)이 채운다 — 스텝 인터페이스·플러그인 빈 없음.
- `ProcessedFood(food, hasAvoidanceMapping)` — processor→writer 로 전이 판정 입력을 넘기는 값.
- **원자성 폐기 근거**: "음식 1건=트랜잭션 1개"는 반쯤 찬 음식 노출을 막으려던 것인데, 그 방어는 READY 게이트(INCOMPLETE 미노출)가 이미 하므로 작업별 부분 커밋이 안전하다.

## 설정

| 키 | 기본값 | 의미 |
|----|--------|------|
| `kbap.batch.content.chunk-size` | 10 | 리더가 한 번에 DB 에서 읽는 INCOMPLETE 건수(처리·커밋은 1건 단위) |
| `spring.batch.job.enabled` | false | 부팅 시 잡 자동 실행 여부(실행 시 `--spring.batch.job.enabled=true`) |
| `spring.batch.jdbc.initialize-schema` | never(main) / always(test) | 메타 테이블 생성 주체(운영=api Flyway, 테스트=Batch 자체) |

## Spring Batch 메타데이터

`BATCH_*` 6테이블(+시퀀스). 배치는 `flyway off` 라 **api Flyway 마이그레이션**(`V2026.07.21…__spring_batch_metadata.sql`)이 생성 — 원본은 spring-batch-core `schema-mysql.sql`(Batch 6.0). 테스트는 Flyway off 이므로 `initialize-schema=always` 로 Batch 가 Testcontainer 에 직접 생성.
