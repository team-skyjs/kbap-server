# Phase 1 Data Model: 미지원 언어 코드 strict 검증

이번 기능은 **영속 스키마·엔티티 변경이 없다**. 다루는 대상은 언어 코드 해석 규칙과 에러 표면이며, 개념 모델은 다음과 같다.

## 개념 모델

### LanguageCode (공유 커널 어휘, 기존)
- 위치: `com.meogo.core.kernel.lang.LanguageCode` (enum)
- 값: `KO("ko")`, `ZH_HANS("zh-Hans")`, `EN("en")`, `JA("ja")`, `ZH_HANT("zh-Hant")`, `VI("vi")`, `ID("id")`, `TH("th")`, `RU("ru")`, `ES("es")` — **지원 언어 10종의 단일 출처**.
- 해석 규칙(변경):
  - 입력 `code` 가 `null`·빈 문자열·공백뿐 → `KO` (기본, 에러 아님)
  - trim 후 `entries` 의 `code` 와 **정확 일치** → 해당 `LanguageCode`
  - 그 외(값 존재 + 불일치) → `UnsupportedLanguageException` throw
- 지원 목록 표현: `entries.joinToString(", ") { it.code }` → `"ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es"` (안내 메시지 단일 출처)

### UnsupportedLanguageException (신규, 커널 예외)
- 위치: `com.meogo.core.kernel.lang.UnsupportedLanguageException`
- 상위 타입: `IllegalArgumentException`
- 속성:
  - `requestedCode: String` — 클라이언트가 보낸 미지원 코드(trim 후)
  - `message` — 미지원 코드 + 지원 언어 목록을 포함한 안내 문구
- 불변식: 지원 목록에 **없는** 코드에 대해서만 생성된다(값이 있으나 미지원).
- Spring-free: 순수 Kotlin 예외 — kernel 의 Spring-free 제약 준수.

## 상태/흐름

```
lang(String?) ─▶ LanguageCode 해석
                   ├─ null / "" / "   "        ─▶ KO (성공 흐름 계속)
                   ├─ 정확 일치 (ko, en, ...)   ─▶ 해당 LanguageCode (성공 흐름 계속)
                   └─ 값 존재 + 불일치           ─▶ UnsupportedLanguageException
                                                     ─▶ GlobalExceptionHandler
                                                        ─▶ 400 + BaseResponse.fail(지원 목록 메시지)
```

## 검증 규칙 (요구사항 매핑)

| 규칙 | 출처 | 위치 |
|------|------|------|
| 값 존재 + 정확 불일치 → 에러 | FR-001, FR-004 | `LanguageCode` 해석 |
| 에러 메시지에 지원 목록 10종 포함 | FR-002 | `UnsupportedLanguageException` / `LanguageCode.entries` |
| null·빈·공백 → `KO` 기본 | FR-003 | `LanguageCode` 해석 |
| 미지원 코드 조회 → 400 + `BaseResponse.fail` | FR-005, FR-006 | `GlobalExceptionHandler` |
| 지원 언어이나 번역 부재 → `ko` 폴백 유지 | FR-007 | `GetFoodDetailUseCase` (변경 없음) |
| 공유 어휘 단일 규칙 강제 | FR-008 | `LanguageCode` (kernel) |
