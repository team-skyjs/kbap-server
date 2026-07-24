# Phase 0 Research: 미지원 언어 코드 strict 검증

## 현행 동작 조사 (코드 기준)

- `com.meogo.core.kernel.lang.LanguageCode.from(code)` 는 `entries.firstOrNull { it.code == code?.trim() } ?: KO` — **미지원/오타/대소문자 불일치/null/blank 모두 조용히 `KO`** 로 반환한다.
- `from` 의 유일한 프로덕션 소비자는 `LanguageResolver.resolve(lang)`(`:application:client`).
- `LanguageResolver.resolve` 의 유일한 소비자는 `GetFoodDetailUseCase`(음식 상세조회). **메뉴 스캔(`MenuScanController`·scan usecase)은 `lang` 을 받지 않는다** — 이슈 #18 의 MenuScanController 언급은 현행 코드와 불일치(사용자 표면 회귀 대상은 음식 상세조회로 한정).
- `:app:api` 에는 이미 `GlobalExceptionHandler`(`@RestControllerAdvice`) 가 존재하며, `IllegalArgumentException` → **400 + `BaseResponse.fail(e.message)`** 로 매핑한다. 음식 상세조회의 기존 400("해당 음식 정보 없음"·"menuName은 필수입니다")이 이 경로를 탄다.

## 결정 사항

### Decision 1 — strict 해석 규칙의 위치와 시그니처
- **Decision**: `LanguageCode` 에 strict 해석을 두고 `from` 을 strict 로 **전면 교체**한다. 규칙: `code` 가 null·빈·공백 → `KO`; trim 후 `entries` 와 **정확 일치** → 해당 코드; 그 외(값 존재 + 불일치) → `UnsupportedLanguageException`.
- **Rationale**: 공유 어휘(kernel) 단일 규칙으로 모든 소비자가 동일 동작을 상속(FR-008). 별도 lenient/strict 두 경로를 남기면 조용한 폴백이 다시 새어나갈 위험 → 이슈 권장대로 전면 교체.
- **Alternatives considered**: (a) `from`(lenient) 유지 + `fromStrict` 추가 → 두 경로 공존이 회귀 위험·혼동. 기각. (b) resolver 에서만 검증 → kernel 소비처마다 재구현 필요, 단일 출처 위반. 기각.

### Decision 2 — 예외 타입과 위치
- **Decision (개정)**: **도메인 예외 계층 + ErrorCode 계약 패턴**을 도입한다. `:core:kernel` 에 공유 계약 `com.meogo.core.kernel.error.ErrorCode`(`status: Int`·`message: String` 인터페이스)와 최상위 추상 예외 `MeogoException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)` 를 둔다. 언어는 kernel 소유 어휘이므로 `com.meogo.core.kernel.lang` 에 `LanguageErrorCode`(enum : ErrorCode, `UNSUPPORTED_LANGUAGE(400, …)`)·도메인 예외 `LanguageException(errorCode: LanguageErrorCode) : MeogoException(errorCode)`(**open class**, throw 시 enum 전달 — `throw LanguageException(LanguageErrorCode.UNSUPPORTED_LANGUAGE)`, 코드별 전용 하위 클래스 없음) 를 둔다. 지원 목록은 `LanguageCode.entries.joinToString(", ") { it.code }` 로 enum 메시지에서 **단일 출처** 생성. 메시지 문자열은 throw 지점/핸들러가 아니라 **ErrorCode enum 이 소유**(문자열 직접 작성 금지).
- **Rationale**: (1) 최상위 `MeogoException` 하나를 핸들러가 잡으면 **모든 도메인·모든 하위 예외를 단일 핸들러로 커버**(사용자 요구 "대표 예외만 체크"). (2) 도메인별 `ErrorCode` enum 이 상태코드+메시지를 소유해 도메인이 자기 오류 vocabulary 를 **바운디드 컨텍스트 안에서 관리**(원칙 II — food/avoidance 코드가 kernel 로 새지 않음; 언어만 kernel 소유라 kernel.lang). (3) kernel Spring-free 유지(순수 Kotlin, RuntimeException 기반). status 는 정수라 kernel 이 Spring `HttpStatus` 를 모름.
- **Alternatives considered**: (a) `IllegalArgumentException` 상속 + 기존 IAE 핸들러 재사용 → 변경은 최소지만 무관한 IAE(`require`·"해당 음식 정보 없음")까지 뭉뚱그려져 의도·상태 매핑이 흐려짐. 기각. (b) 도메인 부모별 핸들러(공유 루트 없음) → 도메인 추가마다 동일 핸들러 복붙. 기각. (c) 전 도메인 에러 코드를 kernel 단일 enum 에 집約 → 원칙 II(컨텍스트 소유권) 위반(#21 AvoidanceSubstance 이관과 동일 사유). 기각.

### Decision 3 — 에러 매핑 (400) 방식 (개정)
- **Decision**: `GlobalExceptionHandler` 에 **`MeogoException` 최상위 핸들러 1개**를 두고 `ResponseEntity.status(HttpStatus.valueOf(e.errorCode.status)).body(BaseResponse.fail(e.errorCode.message))` 로 매핑한다. 기존 `MethodArgumentNotValidException`·`HttpMessageNotReadableException`·`IllegalArgumentException`(require·food not-found) 핸들러는 유지. 예상 못 한 기술 오류는 이 계층 밖(일반 `Exception` fallback → 500).
- **Rationale**: 도메인이 늘어도 핸들러 불변 — 상태·메시지는 `errorCode` 가 운반. status 정수 → web 계층에서 `HttpStatus.valueOf` 로 매핑(kernel 은 Spring 무의존). 응답 규약(`ResponseEntity<BaseResponse<T>>`) 준수.
- **Alternatives considered**: 예외 타입별 전용 핸들러 다수 → 도메인 확장 시 보일러플레이트. 단일 루트 핸들러 채택.

### Decision 4 — 기본값·매칭 규칙 (사용자 확정)
- **Decision**: null·빈 문자열·공백 → `KO` 기본(에러 아님). 매칭은 **정확 일치**(`EN`·`ko-KR`·`en-US` 등은 미지원 에러, 정규화 없음).
- **Rationale**: `lang` 은 선택 파라미터(`required=false`)라 "미지정=한국어"가 자연스러움. 정규화를 도입하면 "지원 코드 표기 흔들림"을 조용히 수용해 fail-fast 취지가 흐려짐. (spec Assumptions·사용자 확인)

### Decision 5 — 헌법 원칙 V 정합
- **Decision**: 원칙 V 의 "미지원/미지정 언어 → `ko` 폴백" 문구를 "미지정(null·빈·공백) → `ko` 폴백 / 지원 언어이나 번역 부재 → `ko` 폴백 / 지원 목록에 없는 코드 → 에러 + 지원 목록 안내"로 **MINOR 개정**한다.
- **Rationale**: 이번 기능이 원칙 V 의 폴백 정책을 실질 변경하므로 헌법을 현행화해야 거버넌스 정합(원칙 준수 검증 통과). 제거/비호환이 아니라 폴백 조건을 세분화하는 확장이라 MINOR.
- **Action**: 구현 머지 전 `/speckit-constitution` 실행(별도 단계).

## 회귀 영향 (갱신 필요 테스트)

| 테스트 | 기존 기대 | 변경 후 기대 |
|--------|-----------|--------------|
| `LanguageCodeTest` | `from("xx")`·`from("EN")` → `KO` | `xx`·`EN` → `UnsupportedLanguageException` (null·`""`·`"   "` → `KO` 유지) |
| `LanguageResolverTest` | `resolve("xx")` → `KO` | `resolve("xx")` → 예외 (null·blank → `KO` 유지) |
| `FoodDetailLangTest` | `lang=xx` → 200 + 한국어 폴백 | `lang=xx` → 400 + `success=false` + 지원 목록 메시지 (`lang=ja` 성공·`lang` 미지정 한국어는 유지) |
| (신규) `FoodDetailLanguageErrorTest` | — | 미지원 코드 → 400 + 지원 언어 10종 목록 메시지 검증 |
