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

### 예외 계층 (신규, 도메인 예외 + ErrorCode 계약)
공유 계약과 최상위 예외는 `:core:kernel`, 언어 오류 vocabulary 는 kernel 소유라 `kernel.lang` 에 둔다. 타 도메인(food·avoidance…)은 자기 모듈에 같은 패턴으로 추가한다(원칙 II).

- `com.meogo.core.kernel.error.ErrorCode` (interface) — `status: Int`·`message: String`. "예외 종류"의 공유 계약.
- `com.meogo.core.kernel.error.MeogoException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)` — 예상된 비즈니스/도메인 오류의 **최상위 추상 예외**. 핸들러가 이 하나만 잡아 전 하위 커버.
- `com.meogo.core.kernel.lang.LanguageErrorCode` (enum : ErrorCode) — `UNSUPPORTED_LANGUAGE(400, "지원하지 않는 언어 코드입니다. 지원 언어: " + LanguageCode.entries…)`. 상태코드+메시지 **단일 출처**(문자열 직접 작성 금지).
- `com.meogo.core.kernel.lang.LanguageException(errorCode: LanguageErrorCode) : MeogoException(errorCode)` — 언어 도메인 예외. **`open class`** 로 두고 **throw 시 enum 을 전달**한다(`throw LanguageException(LanguageErrorCode.UNSUPPORTED_LANGUAGE)`). 오류 종류는 enum 이 구분하므로 코드마다 전용 하위 클래스를 두지 않는다(특정 오류만 타입 분기 필요 시 그때 하위 클래스로 승격).
  - 핸들러가 구체 타입으로 분기하지 않고 `errorCode` 만 읽으므로, "코드별 전용 예외 클래스" 대신 "enum 을 던질 때 전달"이 설계와 정합.
- Spring-free: 전부 순수 Kotlin(RuntimeException 기반, status 는 정수) — kernel 의 Spring-free 제약 준수. HTTP 상태 매핑·로깅은 web 핸들러에서(`HttpStatus.resolve`; 4xx→warn(스택 제외), 5xx→error(스택 포함)).

## 상태/흐름

```
lang(String?) ─▶ LanguageCode 해석
                   ├─ null / "" / "   "        ─▶ KO (성공 흐름 계속)
                   ├─ 정확 일치 (ko, en, ...)   ─▶ 해당 LanguageCode (성공 흐름 계속)
                   └─ 값 존재 + 불일치           ─▶ LanguageException(LanguageErrorCode.UNSUPPORTED_LANGUAGE) (: MeogoException)
                                                     ─▶ GlobalExceptionHandler @ExceptionHandler(MeogoException)
                                                        ─▶ status(errorCode.status=400) + BaseResponse.fail(errorCode.message)
```

## 검증 규칙 (요구사항 매핑)

| 규칙 | 출처 | 위치 |
|------|------|------|
| 값 존재 + 정확 불일치 → 에러 | FR-001, FR-004 | `LanguageCode` 해석 |
| 에러 메시지에 지원 목록 10종 포함 | FR-002 | `LanguageErrorCode.UNSUPPORTED_LANGUAGE` / `LanguageCode.entries` |
| null·빈·공백 → `KO` 기본 | FR-003 | `LanguageCode` 해석 |
| 미지원 코드 조회 → 400 + `BaseResponse.fail` | FR-005, FR-006 | `GlobalExceptionHandler`(`MeogoException` 핸들러 + `errorCode.status`) |
| 지원 언어이나 번역 부재 → `ko` 폴백 유지 | FR-007 | `GetFoodDetailUseCase` (변경 없음) |
| 공유 어휘 단일 규칙 강제 | FR-008 | `LanguageCode` (kernel) |
