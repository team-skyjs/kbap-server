# Contract: 회피·주의 성분 카탈로그 — `:core:avoidance` 공개 API

이 기능이 노출하는 "인터페이스"는 web 엔드포인트가 아니라 **소유 컨텍스트(avoidance)의 공개 Kotlin 타입**이다(application·app:batch 가 소비). 패키지 `com.meogo.core.avoidance` · 공유 `com.meogo.core.kernel.lang`.

## 공개 타입 시그니처

```kotlin
package com.meogo.core.avoidance

enum class AvoidanceCategory { ALLERGEN, DIETARY_RULE, PERSONAL_AVOIDANCE }

enum class AvoidanceSubstance(
    val categories: Set<AvoidanceCategory>,
    val koName: String,
) {
    // 81종 — 예시(mock):
    // PEANUT(setOf(AvoidanceCategory.ALLERGEN), "땅콩"),
    // PORK(setOf(AvoidanceCategory.ALLERGEN, AvoidanceCategory.DIETARY_RULE), "돼지고기"),
    ;
    // init { require(categories.isNotEmpty() && categories.size <= 3) ; require(koName.isNotBlank()) }
}

object AvoidanceCatalog {
    fun displayName(substance: AvoidanceSubstance, lang: LanguageCode): String
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>
    fun all(): List<AvoidanceSubstance>            // = AvoidanceSubstance.entries
}
```

```kotlin
package com.meogo.core.kernel.lang   // ← core.food 에서 이동

enum class LanguageCode(val code: String) {
    KO("ko"), ZH_HANS("zh-Hans"), EN("en"), JA("ja"), ZH_HANT("zh-Hant"),
    VI("vi"), ID("id"), TH("th"), RU("ru"), ES("es");
    companion object { fun from(code: String?): LanguageCode }
}
```

## 동작 계약 (소비자가 의존해도 되는 보장)

| ID | 계약 | 검증(테스트) |
|----|------|------|
| C-1 | `AvoidanceSubstance.entries` 는 81종이며 각 `name`(코드) 유일 | AvoidanceSubstanceTest |
| C-2 | 모든 성분 `categories.size` ∈ [1,3], 중복 없음(Set) | AvoidanceSubstanceTest |
| C-3 | 모든 성분 `koName` 비공백 | AvoidanceSubstanceTest |
| C-4 | `AvoidanceCategory` 는 정확히 3값 | AvoidanceSubstanceTest |
| C-5 | `displayName(s, KO)` == `s.koName` | AvoidanceCatalogTest |
| C-6 | `displayName(s, lang)` 은 등록 번역 있으면 그 값, 없으면 `s.koName` 폴백, 절대 빈 문자열 아님 | AvoidanceCatalogTest |
| C-7 | `byCategory(c)` 는 `c` 를 포함하는 모든 성분(복수 분류 성분 포함), 그 외 제외 | AvoidanceCatalogTest |
| C-8 | 모든 성분이 9개 대상 언어 번역 키 보유(완전성) 또는 폴백으로 안전 | AvoidanceCatalogTest |

## 소비 측 (참고 — 본 범위 밖, 후속)

- `app:batch`: `implementation(:core:avoidance)` 추가 후 LLM 프롬프트에 `AvoidanceCatalog.all()`/`byCategory` 로 코드·분류 주입.
- `application`(조합·판정): food·member 를 모아 코드→enum 변환, 표시 명칭은 `displayName(s, userLang)`. `app:api` 는 application 의 공개 DTO 만 본다(직접 의존 X — 원칙 IV/ArchUnit).
- member 는 enum 미import, 사용자 회피 선호를 **코드 문자열**로 저장(원칙 II). food 는 enum 무관.
