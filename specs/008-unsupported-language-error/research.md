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
- **Decision**: `com.meogo.core.kernel.lang.UnsupportedLanguageException` 신규. `IllegalArgumentException` 을 상속하고, 메시지에 입력 코드와 지원 목록을 포함한다. 지원 목록 문자열은 `LanguageCode.entries.joinToString(", ") { it.code }` 로 **enum 단일 출처**에서 생성.
- **Rationale**: kernel 은 공통 예외의 소유 위치이자 Spring-free(순수 Kotlin 예외라 부합). `IllegalArgumentException` 상속 시 기존 `GlobalExceptionHandler` 의 IAE 핸들러가 자동으로 400 + 메시지 매핑 → 변경 최소. 지원 목록을 enum 에서 생성해 언어 추가 시 메시지 자동 동기화.
- **Alternatives considered**: (a) kernel 공통 예외 베이스 상속 → 현재 kernel 에 도메인 예외 베이스가 없어 과설계. IAE 상속이 기존 핸들러와 정합. (b) application/web 계층에 예외 배치 → kernel 소비처(타 계층)에서 못 던짐. 기각.

### Decision 3 — 에러 매핑 (400) 방식
- **Decision**: `UnsupportedLanguageException` 전용 `@ExceptionHandler` 를 `GlobalExceptionHandler` 에 **명시 추가**해 400 + `BaseResponse.fail(e.message)` 로 매핑한다(IAE 상속으로 자동 커버되지만, 의도를 코드로 드러내고 메시지 형식을 고정하기 위해 전용 핸들러 유지).
- **Rationale**: Spring 은 가장 구체적인 예외 핸들러를 우선 선택하므로 전용 핸들러가 안정적으로 승리. 향후 상태/메시지 형식 변경 지점을 명확히 한다. 응답 규약(`ResponseEntity<BaseResponse<T>>`) 준수.
- **Alternatives considered**: 전용 핸들러 없이 기존 IAE 핸들러에 위임 → 동작은 되나 "미지원 언어"의 의도가 코드에 드러나지 않음. 명시 핸들러 채택.

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
