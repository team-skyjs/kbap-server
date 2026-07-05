# Quickstart: 삭제된 기피 성분 skip 처리 (KB-47)

## 무엇을 바꾸나

`GetFoodDetailUseCase` 의 성분 조립 루프 한 곳. 카탈로그에 없는(소프트 삭제된) 성분을 예외 대신 skip 하고 WARN 로그를 남긴다.

## 대상 파일

- `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCase.kt` — 수정
- `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/GetFoodDetailUseCaseTest.kt` — 테스트 추가

## TDD 흐름 (원칙 I)

1. **Red** — 아래 시나리오로 실패 테스트를 먼저 추가한다:
   - 참조 성분 2개 중 1개가 카탈로그에 없음(Fake 가 해당 code 미반환) → 예외 없이 나머지 1개만 조립.
   - 참조 성분 전부 카탈로그에 없음 → `avoidanceSubstances` 빈 목록, `spiciness`·`name` 정상.
   - (회귀) 전부 존재 → 기존 테스트 그대로 통과.
   - skip 발생 시 `GetFoodDetailUseCase` 로거에 WARN 이벤트가 남고 메시지에 `foodId`·`substanceCode` 포함.
   - 실패(Red) 확인: `./gradlew :application:client:test`.

2. **Green** — 조립 루프를 `map { … ?: throw }` → `partition` 으로 바꿔 존재/부재를 가르고, 부재는 WARN 로그, 존재만 조립한다(`null` 미사용):

   ```kotlin
   private val log = LoggerFactory.getLogger(GetFoodDetailUseCase::class.java)
   // ...
   val (resolvable, missing) = codedSubstances.partition { (_, code) -> code in catalog }

   missing.forEach { (_, code) ->
       log.warn(
           "avoidance substance skipped (catalog missing / soft-deleted): foodId={} substanceCode={}",
           food.id, code,
       )
   }

   val avoidanceSubstances = resolvable.map { (substance, code) ->
       val catalogEntry = catalog.getValue(code)
       GetFoodDetailResult.AvoidanceSubstanceView(
           name = catalogEntry.displayName(lang),
           iconRef = null,
           inclusionProbability = substance.inclusionProbability,
           riskStatus = risks[substance.substanceCode.value] ?: RiskLevel.SAFE,
       )
   }
   ```

3. **Refactor** — 필요 시 로그 메시지 상수화/정리. 계약·정렬·위험도 로직은 건드리지 않는다.

## 로그 검증 스니펫 (테스트)

```kotlin
val logger = LoggerFactory.getLogger(GetFoodDetailUseCase::class.java) as ch.qos.logback.classic.Logger
val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
logger.addAppender(appender)
// ... getDetail 호출 ...
appender.list.any { it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains("SOY") } shouldBe true
logger.detachAppender(appender)
```

## 검증

```bash
./gradlew :application:client:test          # 유스케이스 테스트(신규 시나리오 포함)
./gradlew build                             # 전체 회귀 (ArchUnit 경계 포함)
```

**주의**: DB·마이그레이션·엔티티·응답 DTO 변경 없음 — Flyway/H2 관련 작업 불필요.
