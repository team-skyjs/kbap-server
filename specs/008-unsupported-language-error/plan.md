# Implementation Plan: 미지원 언어 코드 요청 시 에러 응답 (LanguageCode strict 검증)

**Branch**: `008-unsupported-language-error` | **Date**: 2026-07-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-unsupported-language-error/spec.md`

## Summary

언어 파라미터를 받는 조회에서 **값이 있으나 지원 목록에 없는 언어 코드**가 들어오면 조용히 `ko` 로 폴백하던 기존 동작을 폐지하고, **미지원 언어 에러**(지원 언어 목록 안내 포함)를 던져 **HTTP 400 + `BaseResponse.fail`** 로 응답한다. 미지정(null·빈·공백)은 기존대로 `ko` 기본을 유지하고, 지원 언어이나 번역이 없는 경우의 `ko` 폴백도 유지한다.

기술 접근: 공유 커널 어휘 `LanguageCode` 에 **strict 해석 규칙**을 두어(값 존재 + 정확 불일치 → 예외), 이 어휘를 소비하는 모든 조회가 단일 규칙을 상속하게 한다. 예외는 kernel 에 두고(`UnsupportedLanguageException`, 지원 목록을 메시지에 포함), `LanguageResolver` 가 strict 경로를 사용하며, `GlobalExceptionHandler` 가 400 으로 매핑한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web/validation), Kotest BehaviorSpec (+ kotest-extensions-spring), springdoc-openapi

**Storage**: N/A — 이번 변경은 언어 코드 해석/에러 표면만 다루며 스키마·엔티티 변경 없음

**Testing**: Kotest BehaviorSpec — kernel 단위(`:core:kernel`), application 단위(`:application:client` `LanguageResolver`), web MockMvc(`:app:api` 음식 상세조회)

**Target Platform**: Linux server (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 web service (기존 구조 재사용)

**Performance Goals**: N/A — 입력 검증 경로, 성능 영향 없음

**Constraints**: 응답 규약 `ResponseEntity<BaseResponse<T>>` 준수, 경로 규약 `/api/v` 유지, Kotlin 주석 금지, 도메인/커널 Spring-free 유지

**Scale/Scope**: 소규모 — 프로덕션 코드 3파일 수정/추가(`LanguageCode`·`UnsupportedLanguageException`·`LanguageResolver`·`GlobalExceptionHandler`), 회귀 테스트 3종 갱신 + 신규 에러 테스트

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **원칙 I — Test-First (NON-NEGOTIABLE)**: ✅ 준수. 모든 변경은 실패 테스트 선작성 → Green → Refactor. kernel 단위·resolver 단위·web MockMvc 3계층 모두 Red 우선.
- **원칙 II — Bounded Contexts**: ✅ 준수. 변경 대상은 공유 어휘(kernel)·application·web 뿐. 도메인 간 직접 결합 없음. `LanguageCode` 는 원래 kernel 의 공유 vocabulary 이므로 소유 위치 적절.
- **원칙 III — Layered Dependency Direction**: ✅ 준수. `UnsupportedLanguageException` → kernel(모두 의존 가능·Spring-free), 해석 사용 → application, 에러 매핑 → app:api. 의존 방향 하향 유지. kernel 은 Spring-free 유지(예외는 순수 Kotlin).
- **원칙 IV — Persistence Encapsulation**: ✅ 해당 없음. 영속 변경 없음.
- **원칙 V — Domain Content Language Policy**: ✅ **개정 완료(v2.3.0, 2026-07-02)**. 기존 *"미지원/미지정 언어 → `ko` 폴백"* 문구를 세 경우로 분리했다 — (1) 미지정(null·빈·공백) → `ko` 기본, (2) 지원 언어이나 번역 부재 → `ko` 폴백, (3) 지원 목록에 없는 코드(값 존재 + 정확 불일치) → 에러(fail-fast) + 지원 목록 안내(HTTP 400), 매칭은 정확 일치. 이번 기능 설계와 헌법이 일치한다.

**게이트 판정**: 원칙 I~V 통과. (원칙 V 는 v2.3.0 개정으로 정합 완료 — 아래 Complexity Tracking 은 개정 경위 기록용.)

## Project Structure

### Documentation (this feature)

```text
specs/008-unsupported-language-error/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── food-detail-language.md
├── checklists/
│   └── requirements.md  # /speckit-specify output
└── tasks.md             # /speckit-tasks output (미생성)
```

### Source Code (repository root)

```text
core/kernel/src/main/kotlin/com/meogo/core/kernel/error/
├── ErrorCode.kt                       # 신규 — 예외 종류 계약(status·message) 인터페이스
└── MeogoException.kt                  # 신규 — 예상된 비즈니스/도메인 오류 최상위 추상 예외

core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/
├── LanguageCode.kt                    # strict 해석 규칙 추가 (미지원 → 예외, null·blank → KO)
├── LanguageErrorCode.kt               # 신규 — enum : ErrorCode, UNSUPPORTED_LANGUAGE(400, 지원목록)
└── LanguageException.kt               # 신규 — open class(errorCode) : MeogoException, throw 시 enum 전달

core/kernel/src/test/kotlin/com/meogo/core/kernel/lang/
└── LanguageCodeTest.kt                # 회귀 갱신 — xx·EN → 예외, null·blank → KO

application/client/src/main/kotlin/com/meogo/application/client/food/usecase/
└── LanguageResolver.kt                # strict 경로 사용으로 변경

application/client/src/test/kotlin/com/meogo/application/client/food/usecase/
└── LanguageResolverTest.kt            # 회귀 갱신 — 미지원 코드 → 예외

app/api/src/main/kotlin/com/meogo/app/api/common/
└── GlobalExceptionHandler.kt          # MeogoException 최상위 핸들러 → status(errorCode) + BaseResponse.fail 매핑

app/api/src/main/kotlin/com/meogo/app/api/food/
└── FoodDetailApi.kt                   # Swagger 문서 갱신 (미지원 lang → 400)

app/api/src/test/kotlin/com/meogo/app/api/food/
├── FoodDetailLangTest.kt              # 회귀 갱신 — lang=xx → 400 (기존 ko 폴백 케이스 제거)
└── FoodDetailLanguageErrorTest.kt     # 신규 — 미지원 코드 400 + 지원 목록 메시지 검증
```

**Structure Decision**: 기존 모듈러 모놀리스 구조를 그대로 사용한다. 신규 모듈 없음, 신규 패키지 `com.meogo.core.kernel.error`(공유 예외 계약) 추가. 공유 어휘·예외 계약은 `:core:kernel` 에, 언어 오류 vocabulary 는 kernel 소유라 `kernel.lang` 에, 해석 사용은 `:application:client` 에, 에러 표면(핸들러)은 `:app:api` 에 두어 계층 방향을 준수한다. 타 도메인(food·avoidance…)은 자기 `ErrorCode` enum·도메인 부모 예외를 소유 모듈에 두는 패턴(원칙 II).

## Complexity Tracking

> Constitution Check 의 원칙 V 위반(의도된 정책 변경)만 기록한다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 V 의 "미지원 언어 → `ko` 폴백" 문구를 "미지원 코드 → 에러"로 변경 | 외국인 대상 서비스에서 잘못된 언어 요청이 조용히 한국어로 응답돼 오인·디버깅 곤란을 유발(이슈 #18). fail-fast + 지원 목록 안내가 UX·디버깅에 우월 | 조용한 `ko` 폴백 유지는 이슈의 문제 자체를 해결하지 못함. 미지정과 미지원을 구분하지 않으면 "언어 생략(정상)"과 "오타(오류)"를 구별할 수 없음 |

**해소 방법**: 구현 머지 전 `/speckit-constitution` 으로 원칙 V 를 MINOR 개정 — "미지정(null·빈·공백) → `ko` 폴백 / 지원 언어이나 번역 부재 → `ko` 폴백 / 지원 목록에 없는 코드 → 에러 + 지원 목록 안내"로 분리 서술. ko 원문·9개 번역·콘텐츠↔UI 분리·안전 정합 등 원칙의 목적은 불변.
