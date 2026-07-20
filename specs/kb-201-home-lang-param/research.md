# Phase 0 Research: lang 파라미터 정책 통일

## R1. `LanguageCode.from` 을 순수 lookup 으로 되돌린다

**Decision**:

```kotlin
fun from(code: String): LanguageCode =
    entries.firstOrNull { it.code == code } ?: EN
```

파라미터는 non-null `String`, trim 없음, 예외 없음. `BusinessException`·`ErrorCode` import 삭제.

**Rationale**: 현재 `from` 은 네 가지 책임을 겹쳐 갖고 있다 — (1) null/빈 값 판정, (2) trim 정규화, (3) 기본값(KO) 결정, (4) 미지원 시 400 발생. 이 중 (1)(3)(4)는 **엔드포인트 정책**이지 언어 코드 vocabulary 의 성질이 아니다. 홈이 다른 정책(EN 폴백)을 원하자 즉시 갈라진 게 그 증거다. 정책을 경계로 올리면 `:core` 에는 "문자열 → enum" 이라는 vocabulary 고유 책임만 남는다.

**trim 제거의 결과(의도적)**: `" ko "` 는 이제 KO 가 아니라 **EN** 이다. 헌법이 이미 "매칭은 정확 일치로 하고 관대한 정규화를 하지 않는다"고 규정하므로, trim 제거는 오히려 그 조항에 더 부합한다. 클라이언트가 패딩된 값을 보내는 것은 클라이언트 결함이다.

**Alternatives considered**:
- **`fromOrNull` 을 추가하고 `from`(fail-fast) 을 병존** — 초기 설계안이었다. 홈만 예외로 두면 이탈이 봉인되지만, 엔드포인트마다 정책이 갈릴 때마다 파서가 늘어난다. 전 API 정책 통일이 결정된 이상 불필요. 기각.
- **`from` 은 두고 컨트롤러가 `?: EN` 처리** — `from` 이 여전히 던지므로 컨트롤러가 예외를 잡아야 한다. 정상 흐름을 예외로 다루는 구조. 기각.

## R2. 검증은 요청 경계(컨트롤러)가 전부 책임진다

**Decision**: 각 컨트롤러가 요청 DTO + `@field:NotBlank` 로 누락·빈 값·공백을 400 으로 쳐내고, `LanguageCode.from(...)` 으로 표시 언어를 확정해 서비스에 넘긴다. 서비스는 `LanguageCode`(non-null)를 받는다.

**Rationale**: "요청이 언어를 안 줬을 수도 있다"는 web 프로토콜 상태를 도메인 서비스가 알 필요가 없다. 타입이 계약을 강제하므로 서비스 안에 방어 코드가 필요 없고, 필수 여부가 Swagger 에도 자동으로 드러난다.

**400 응답에 신규 코드는 없다**: `@NotBlank` 위반 → `MethodArgumentNotValidException` → 기존 `handleValidation`(`GlobalExceptionHandler.kt:20`) → 400 `COMMON-002`. 설령 바인딩 경로 차이로 `BindException` 이 되어도 둘 다 `ErrorResponse` 라 `handleUnexpected` 의 분기(`GlobalExceptionHandler.kt:74-79`)가 400 을 보존한다.

**기존 관례와의 관계**: 이 코드베이스에서 `@Valid` + `*Request` DTO 는 지금까지 **POST/PUT 본문 전용**이었고(`LoginRequest`·`ScanRequest`·`BookmarkCreateRequest`·`UploadUrlRequest`·`ImageCompleteRequest`), 쿼리 파라미터는 raw `@RequestParam` 으로 받아 서비스에서 파싱했다. 이 기능이 **쿼리 파라미터 검증을 경계로 올리는 첫 사례**이며, 이후 신규 API 와 기존 API 이행은 이 패턴을 따른다.

**Alternatives considered**:
- **`@RequestParam @NotBlank lang: String` + `@Validated`** — DTO 클래스는 안 생기지만 `ConstraintViolationException` 이 던져져 **신규 예외 핸들러가 필요**하다(기존 핸들러 미처리). DTO 쪽이 싸다. 기각.
- **서비스가 `String` 을 받아 파싱(현행 유지)** — 필수 계약이 타입에 드러나지 않고, 엔드포인트별 정책이 다시 도메인으로 스며든다. 기각.

## R3. 미지정에 기본값(ko)을 두지 않는다

**Decision**: `@RequestParam(defaultValue = "ko")` 를 쓰지 않는다. 전 API 에서 `lang` 은 필수이며 누락은 400 이다.

**Rationale**: 기본값을 두면 클라이언트가 `lang` 을 빠뜨린 버그가 정상 응답(한국어)에 묻혀 드러나지 않는다. 미지원 코드를 조용히 폴백하기로 한 이상(R4), **최소한 "값을 안 보낸 것"만큼은 시끄럽게 실패해야** 결함 탐지 경로가 하나는 남는다.

**부수 효과**: `defaultValue` 는 파라미터가 **없을 때만** 적용되므로 `?lang=` 빈 문자열은 통과했을 것이다. `@NotBlank` 는 누락·빈 값·공백을 하나의 규칙으로 묶어 이 틈을 없앤다.

## R4. 미지원 코드는 400 이 아니라 영어

**Decision**: 지원 목록에 없는 값은 전 API 에서 `LanguageCode.EN` 으로 폴백한다. `ErrorCode.UNSUPPORTED_LANGUAGE`(`COMMON-001`)를 삭제한다.

**Rationale**: `lang` 은 **기기 설정에서 흘러드는 값**이지 사용자가 고른 값이 아니다. 서비스가 지원하지 않는 기기 언어(예: `fr`)를 쓰는 사용자에게 400 을 주면 화면이 열리지 않으며, 홈은 앱 진입 화면이라 앱 자체가 열리지 않는 것과 같다. 클라이언트가 지원 목록을 알고 걸러내게 하면 목록을 이중으로 유지해야 하고, 서버가 언어를 추가할 때마다 앱 배포가 필요해진다.

**기존 근거와의 대비**: 헌법 원칙 V 의 fail-fast 근거는 "잘못된 코드가 **조용히 한국어로** 응답되면 오인·디버깅을 어렵게 한다"였다. 영어 폴백은 외국인 대상 서비스에서 성격이 다르다 — 한국어 폴백은 대상 사용자가 읽을 수 없지만 영어는 상당수가 읽는다.

**감수하는 비용(명시)**: 클라이언트 오타(`jp`)나 대소문자 오류(`EN`)가 200 영어로 조용히 나간다. 특히 `EN` → 영어는 "우연히 맞아 보이는" 결과라 결함이 영원히 드러나지 않을 수 있다. R3 의 필수화가 부분적 방어이며, 이 트레이드오프를 ADR-0013 에 명시적으로 남긴다.

**Alternatives considered**: `ko` 폴백 — 비한국어권 외국인 대상 서비스의 목적에 정면으로 반한다. 기각.

## R5. 홈은 회원 프로필 언어를 참조하지 않는다

**Decision**: `member.profile.appLanguage` 를 홈의 언어 결정에서 제거한다. 회원·비회원이 동일한 규칙을 따른다.

**Rationale**: 표시 언어 결정 경로를 요청 하나로 고정하는 것이 이 기능의 목적이다. 홈만 프로필을 보면 같은 세션에서 홈과 상세의 언어가 어긋나는 원래 문제가 남는다.

**주의**: `memberId` 자체는 계속 필요하다 — 기피 성분·최근 스캔·북마크 판정에 쓰인다. 제거되는 것은 언어 결정에서의 프로필 의존뿐이다.

**남는 예외**: 스캔 API(`ScanService.kt:38`)는 `lang` 파라미터가 없고 프로필을 계속 참조한다. 이 기능의 대상이 아니며, CLAUDE.md 의 "프로필 언어 = 회원 응답 언어의 단일 기준" 서술을 **스캔 한정**으로 정정한다.

## R6. 거버넌스 산출물

**Decision**: 코드 변경과 함께 세 가지를 처리하되 **별도 커밋**으로 분리해 리뷰 포인트를 나눈다.

| 산출물 | 내용 |
|---|---|
| 헌법 원칙 V 개정 | clause (3) 을 영어 폴백으로 교체, clause (1) 을 필수화에 맞춰 정리. clause (2)(번역 부재 → ko)와 "정확 일치·정규화 금지"는 유지. MAJOR 범프 + Sync Impact Report 갱신 |
| spec 008 supersede | `008-unsupported-language-error` 에 superseded 표기와 대체 포인터 |
| ADR-0013 | fail-fast → 영어 폴백 전환의 근거·대안·트레이드오프(조용한 실패) |

**Rationale**: 헌법 개정이 기능 리뷰에 섞이면 둘 다 제대로 검토되지 않는다. 커밋을 분리하면 리뷰어가 "언어 정책 변경"과 "그 구현"을 따로 판단할 수 있다.

## R7. 기존 테스트 영향

> **2026-07-20 구현 착수 시 전수 조사로 갱신** — 초기 표에 없던 회귀 지점 3개를 아래 ⚠️ 로 표시했다.

| 대상 | 조치 |
|---|---|
| `LanguageCodeTest` | `from(null)`·`from("")`·`from("   ")` → KO 케이스 3개 **삭제**(시그니처가 non-null). `shouldThrow` 케이스 5개(`"xx"`·`"EN"`·`"ko-KR"`·`" fr "`·`"fr"` 메시지 검증) → **EN 반환 기대로 전환**. `" ko "` → EN 케이스 **신규**(trim 제거 증명) |
| `FoodSearchControllerTest` | `ErrorCode.UNSUPPORTED_LANGUAGE.message` 참조 2곳(375·391행 부근) — 400 기대를 200 + 영어 표시명 기대로 전환. 엔드포인트 호출 26곳에 `lang` 추가 |
| ⚠️ **`FoodDetailLangTest`** | **현재 동작을 명시적으로 고정하고 있는 케이스 3개가 정면 충돌한다** — `lang` 미지정(77행)·빈 값(86행)·공백(97행) 이 각각 `ko` 응답을 기대한다. 전부 **400 `COMMON-002` 기대로 반전**해야 한다. `lang=xx`(66행) → 400 기대는 **EN 기대로 반전**. 초기 조사 누락분 |
| ⚠️ **`ScenarioApiDriver` + 시나리오 4종** | **E2E 드라이버가 대상 엔드포인트 4곳을 `lang` 없이 호출한다**(62·65·70·74행 — 홈·검색·상세·북마크목록). 수정하지 않으면 `HappyPathScenarioTest`·`MenuScanScenarioTest`·`WithdrawScenarioTest`·`AuthLifecycleScenarioTest` 가 **전부 400 으로 깨진다**. 드라이버에 `lang` 을 추가하는 것이 단일 수정 지점. 초기 조사 누락분 |
| ⚠️ **`GlobalExceptionHandlerTest`** | 이미 존재한다(404·405·500·비즈니스 예외 커버). 신규 파일을 만들지 말고 **이 파일에 400 `COMMON-002`(필수 파라미터 누락) 케이스를 추가**한다. 초기 계획의 "신규 테스트 추가" 서술 정정 |
| `HomeControllerTest` | 프로필 JA 회원 → 일본어 기대를 `lang=ko` → **한국어**로 반전(프로필 무시 증명). 프로필 없는 회원 → 영어 기대를 `lang=ja` → 일본어로 반전 |
| `HomeGuestTest` | `lang=en` 명시로 기존 케이스 유지 + `lang=ja` 반영·`lang=fr` 폴백·누락/빈 값 400 추가 |
| 음식·북마크 통합 테스트 | `lang` 없이 호출하던 케이스 전부 400 이 되므로 호출부에 `lang` 추가 |

### 호출부 수정 규모(실측)

| 파일 | `api/v1/` 호출 | `lang` 추가 필요 |
|---|---:|---:|
| `FoodSearchControllerTest` | 26 | 26 |
| `FoodListControllerTest` | 14 | 14 |
| `FoodDetailControllerTest` | 10 | 10 |
| `FoodDetailLangTest` | 8 | 8(일부는 400 반전) |
| `FoodDetailDescriptionTest` | 4 | 4 |
| `BookmarkControllerTest` | 4 | 4 |
| `FoodDetailErrorTest` · `FoodDetailLanguageErrorTest` | 3 · 3 | 6 |
| `HomeGuestTest` · `HomeControllerTest` | 3 · 1 | 4 |
| ⚠️ `ScenarioApiDriver` | 4 | 4 (시나리오 4종 전체를 살림) |
| **합계** | **80** | **80** |

테스트 헬퍼·드라이버에 `lang: String? = "en"` 기본값을 주어 언어와 무관한 케이스는 인자 없이 두고, 누락 400 케이스만 `lang = null` 로 명시한다 — 80곳을 개별 수정하지 않고 헬퍼 경유로 줄이는 것이 목표다.
