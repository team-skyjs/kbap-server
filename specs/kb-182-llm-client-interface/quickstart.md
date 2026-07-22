# Quickstart: 음식 콘텐츠 생성 seam 사용법

**Plan**: [plan.md](plan.md) | **Contracts**: [contracts/food-content-contracts.md](contracts/food-content-contracts.md)

## 검증 (이번 범위)

```bash
./gradlew :core:test        # DTO 불변 테스트
./gradlew build             # 전체 그린 + ArchUnit (SC-003)
```

부팅 확인: 구현·배선이 없으므로 기존과 동일하게 그린이어야 한다 —
`./gradlew :app:batch:test`(KbapBatchApplication 부팅 테스트 포함).

## 후속 태스크에서 구현하기 (KB-183·184·209)

`:infra:llm` 에 구현 클래스 + `@Bean` 등록(기존 `OpenAiMenuBoardVisionExtractor` 패턴):

```kotlin
class OpenAiFoodNameTranslationClient(...) : FoodNameTranslationClient {
    override fun call(koreanName: String): TargetLanguageTexts { ... }
}
```

사진(KB-184)은 Lambda 경유 구현도 계약을 그대로 만족한다:
Lambda 가 지정 키로 S3 에 저장 → 구현은 그 키를 반환.

## 배치 스텝에서 소비하기

```kotlin
class GenerateNameStep(
    private val nameTranslationClient: FoodNameTranslationClient,
) {
    fun process(food: Food) {
        food.nameTranslations = nameTranslationClient.call(food.koreanName())
            .texts.mapKeys { it.key.code }
    }
}
```

실패는 예외로 전파 → 골격의 음식 1건 격리(try/catch)가 INCOMPLETE 잔류 처리.

## 테스트에서 페이크로 대체하기

```kotlin
val fake = FoodNameTranslationClient { name ->
    TargetLanguageTexts(LanguageCode.entries.filter { it != LanguageCode.KO }.associateWith { "$name-${it.code}" })
}
```

(단일 메서드 인터페이스라 SAM 변환으로 람다 페이크가 된다.)
