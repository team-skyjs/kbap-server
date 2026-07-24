# Quickstart: 미지원 언어 코드 strict 검증

## TDD 순서 (원칙 I)

1. **Red — kernel 단위**: `LanguageCodeTest` 갱신
   - `from("xx")`·`from("EN")`·`from("ko-KR")` → `UnsupportedLanguageException` (`shouldThrow`)
   - `from(null)`·`from("")`·`from("   ")` → `KO` (유지)
   - `from("ko")`·9개 대상 언어 정확 일치 → 각 코드 (유지)
   - 예외 메시지에 지원 목록 10종 포함 검증
2. **Green**: `LanguageCode.from` 을 strict 규칙으로 교체 + `UnsupportedLanguageException` 신규.
3. **Red — application 단위**: `LanguageResolverTest` 갱신 — `resolve("xx")` → 예외, null·blank → `KO`.
4. **Green**: `LanguageResolver.resolve` 는 `LanguageCode.from` 위임이라 자동 통과(확인).
5. **Red — web MockMvc**: `FoodDetailLangTest` 의 `lang=xx` → 400 으로 갱신 + 신규 `FoodDetailLanguageErrorTest`(미지원 코드 400 + 지원 목록 메시지).
6. **Green**: `GlobalExceptionHandler` 에 `UnsupportedLanguageException` → 400 + `BaseResponse.fail` 핸들러 추가.
7. **Refactor**: `FoodDetailApi` Swagger 문서를 "미지원 lang → 400"으로 갱신.
8. **머지 전**: `/speckit-constitution` 으로 원칙 V MINOR 개정.

## 로컬 검증

```bash
./gradlew :core:kernel:test --tests "com.meogo.core.kernel.lang.LanguageCodeTest"
./gradlew :application:client:test --tests "com.meogo.application.client.food.usecase.LanguageResolverTest"
./gradlew :app:api:test --tests "com.meogo.app.api.food.FoodDetailLangTest" --tests "com.meogo.app.api.food.FoodDetailLanguageErrorTest"
./gradlew build
```

## 수용 기준 대응 (spec)

- 미지원 코드 → 400 + 지원 목록 → `FoodDetailLanguageErrorTest`, `LanguageCodeTest`
- 미지정(null·빈·공백) → 한국어 성공 → `FoodDetailLangTest`, `LanguageResolverTest`
- 지원 언어 번역 부재 → 한국어 폴백 유지 → 기존 `FoodDetailLangTest`(`lang=ja` 등) 그대로 통과
- 정확 일치(대소문자·지역 변형 미지원) → `LanguageCodeTest`
