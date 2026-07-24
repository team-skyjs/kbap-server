# Quickstart: 회피·주의 성분 카탈로그

## 무엇이 생기나

소유 컨텍스트 `:core:avoidance` 에 컴파일 상수 카탈로그가, `:core:kernel` 에 공유 `LanguageCode` 가 추가된다. DB·마이그레이션·API·평가 로직 없음. 소비처(batch·application) 배선은 후속.

## 사용 예

```kotlin
import com.meogo.core.avoidance.AvoidanceCatalog
import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.kernel.lang.LanguageCode

// 코드·분류 — batch LLM 프롬프트, application 판정 공유
val all = AvoidanceCatalog.all()                              // 81종
val allergens = AvoidanceCatalog.byCategory(AvoidanceCategory.ALLERGEN)

// 사용자 언어로 표시 명칭(미지원 언어 → ko 폴백)
val nameEn = AvoidanceCatalog.displayName(AvoidanceSubstance.PEANUT, LanguageCode.EN)
val nameRu = AvoidanceCatalog.displayName(AvoidanceSubstance.PEANUT, LanguageCode.from("ru"))

val cats = AvoidanceSubstance.PORK.categories                  // 예: [ALLERGEN, DIETARY_RULE]
```

## 검증

```bash
./gradlew :core:avoidance:test              # 카탈로그 불변식·폴백·완전성
./gradlew :core:kernel:test                  # LanguageCode 이동 회귀
./gradlew build                              # 전체(LanguageCode 이동 후 food/persistence/application 회귀)
```

## TDD 진행(원칙 I)

1. `AvoidanceSubstanceTest`(실패) → 81종·분류 1~3·ko 비공백·분류 도메인 불변식.
2. `AvoidanceCategory`/`AvoidanceSubstance` enum 작성(Green) → mock 항목.
3. `AvoidanceCatalogTest`(실패) → displayName 번역/ko 폴백/byCategory/완전성.
4. `AvoidanceSubstanceTranslations`(mock) + `AvoidanceCatalog` resolver(Green).
5. `LanguageCode` 이동(food→kernel) + 소비처 import 갱신 → `./gradlew build` 그린(동작 불변 회귀).

## 콘텐츠 교체(후속)

확정 81종(코드·분류 집합·ko 명칭·9개 번역) 수령 시 `AvoidanceSubstance` 선언과 `AvoidanceSubstanceTranslations` Map 을 교체. 구조·테스트는 고정이라 값만 바뀐다.
