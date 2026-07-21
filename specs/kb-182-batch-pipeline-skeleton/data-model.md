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

## 잡 구조 (`:app:batch`) — Spring Batch chunk-oriented Step

```text
Job "foodContentJob" (RunIdIncrementer — 실행마다 새 인스턴스, 야간 반복)
 └─ Step "foodContentStep" (chunk, commit-interval=1, faultTolerant.skip)
     ├─ Reader   IncompleteFoodItemReader   getIncompleteFoods(lastReadId, pageSize) 키셋
     ├─ Processor FoodContentItemProcessor   음식 1건 4작업 → ProcessedFood
     │              generateImage / generateDescription / translateNames  (본문 후속)
     │              mapAvoidance → Boolean   (KB-209: API 3개 호출·종합)
     └─ Writer    completeContent(food, hasAvoidanceMapping)  저장·전이
```

- **commit-interval = 1** → 음식 1건 = 트랜잭션 1개. skip 시 형제 음식 재처리(중복 LLM) 없음.
- **faultTolerant().skip(Exception).skipLimit(MAX)** + SkipListener 로그 → 한 음식 실패는 그 건만 건너뜀(INCOMPLETE 잔류, 다음 실행 재시도), 잡 계속.
- Processor 의 4작업 메서드 본문을 후속(KB-183·184·209)이 채운다 — 스텝 인터페이스·플러그인 빈 없음.
- `ProcessedFood(food, hasAvoidanceMapping)` — processor→writer 로 전이 판정 입력을 넘기는 값.

## 설정

| 키 | 기본값 | 의미 |
|----|--------|------|
| `kbap.batch.content.chunk-size` | 10 | 리더가 한 번에 DB 에서 읽는 INCOMPLETE 건수(처리·커밋은 1건 단위) |
| `spring.batch.job.enabled` | false | 부팅 시 잡 자동 실행 여부(실행 시 `--spring.batch.job.enabled=true`) |
| `spring.batch.jdbc.initialize-schema` | never(main) / always(test) | 메타 테이블 생성 주체(운영=api Flyway, 테스트=Batch 자체) |

## Spring Batch 메타데이터

`BATCH_*` 6테이블(+시퀀스). 배치는 `flyway off` 라 **api Flyway 마이그레이션**(`V2026.07.21…__spring_batch_metadata.sql`)이 생성 — 원본은 spring-batch-core `schema-mysql.sql`(Batch 6.0). 테스트는 Flyway off 이므로 `initialize-schema=always` 로 Batch 가 Testcontainer 에 직접 생성.
