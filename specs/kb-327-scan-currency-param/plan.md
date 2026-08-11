# Implementation Plan: 스캔 2.0 통화 환산 기준을 currency 요청 파라미터로 전환

**Branch**: `kb-327-scan-currency-param` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-327-scan-currency-param/spec.md`

## Summary

2.0 스캔 요청에 선택 쿼리 파라미터 `currency`(ISO 4217 코드)를 추가하고, 응답의 통화 환산 정보(`ScanV2Response.currency`) 결정 규칙을 **파라미터 > 회원 프로필 > 없음(null)** 으로 바꾼다. 지원하지 않는 값은 기존 `ErrorCode.INVALID_CURRENCY_CODE`(`MEMBER-010`, 400)로 스캔 실행 전에 실패시킨다.

설계 핵심(연구 결정 [research.md](research.md)): 헌법 원칙 V에 따라 **검증은 요청 경계가 소유한다** — `ScanV2Controller` 가 raw `currency` 문자열을 `CurrencyCode?` 로 확정(잘못된 값이면 throw)해 넘기고, `ScanService` 는 확정 타입을 받아 `requestedCurrency ?: member.profile.currency` 우선순위만 결정한다. 통화 결정이 회원 프로필 존재에 의존하지 않게 되어 비회원 스캔(후속 태스크)의 선행 조건이 된다. 1.0 스캔·응답 형태·환율 스냅샷(`CurrencyCode.krwPerUnit`)은 불변. DB·마이그레이션·인프라 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation), springdoc-openapi (Swagger 문서 애너테이션)

**Storage**: 변경 없음 — 신규 테이블·컬럼·Flyway 마이그레이션 없음. `CurrencyCode` enum 스냅샷과 회원 프로필 통화를 읽기만 한다.

**Testing**: Kotest BehaviorSpec (given/when/then 한국어) + `@SpringBootTest`/MockMvc 통합 테스트, MySQL Testcontainers

**Target Platform**: Linux 서버 (api bootJar)

**Project Type**: web-service (`:api` 모듈 단독 변경)

**Performance Goals**: 스캔 응답 시간 불변 — 통화 결정은 enum 조회뿐, 추가 DB·외부 호출 0건

**Constraints**: 파라미터 미전달 시 기존 2.0 동작 완전 보존(하위 호환 — 버전 번호 유지), 1.0 불변, 잘못된 값은 조용한 fallback 금지(MEMBER-010 실패), 잘못된 값 검증은 비전(LLM) 호출 전 수행

**Scale/Scope**: `:api` 모듈 소스 4개 파일 수정 + 테스트 1개 파일. `:common`·`:batch`·`:infra:*` 무변경

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 실패 테스트(파라미터 우선·잘못된 값 400·미전달 fallback) 선작성 → Red 확인 → 최소 구현. tasks 에서 test-writer → implementer 순서 고정 |
| II. Bounded Contexts | PASS | scan 기능 패키지(`com.kbap.api.scan`)가 이미 소비 중인 공유 vocabulary `CurrencyCode`(`common.domain` 루트)·`MemberService` 재사용. 도메인 간 신규 의존 없음 — `ModuleBoundaryTest` 허용 맵 수정 불필요 |
| III. Layered Dependency Direction | PASS | `com.kbap.api.scan` → `com.kbap.common` 기존 방향 그대로. seam·infra 무관 |
| IV. Persistence Ownership | PASS | 영속·트랜잭션 경계 변경 없음 |
| V. Domain Content Language Policy | PASS | **검증 소유 조항 적용**: `currency` 필수 여부·유효성 판정은 요청 경계(`ScanV2Controller`)가 소유하고 `ScanService` 는 확정 타입(`CurrencyCode?`)을 받는다 — `LanguageCode` 와 동일 패턴. 통화 코드는 ISO 4217 식별자로 번역 콘텐츠 정책과 무관. lang 과 달리 미지원 값을 폴백하지 않는 근거는 research R3 |
| Additional Constraints | PASS | 도메인/영속 모델 응답 직접 노출 없음(`CurrencyResponse` DTO 유지). Spring 애너테이션은 구현 컨트롤러에, swagger 문서는 `ScanV2Api` 인터페이스에 — 파라미터 애너테이션 위치 규약 준수. 버전별 클래스 신설 없음(기존 `ScanV2Controller` 수정 — KB-323 명명 유지) |

**Post-Phase 1 재평가**: PASS 유지 — 설계 산출물(data-model·contracts)에서 새 위반 요소 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-327-scan-currency-param/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output — 설계 결정 5건(R1~R5)
├── data-model.md        # Phase 1 output — 통화 결정 모델(신규 엔티티 없음)
├── quickstart.md        # Phase 1 output — 검증 시나리오
├── checklists/
│   └── requirements.md  # /speckit-specify 품질 체크리스트 (완료)
├── contracts/
│   └── scan-v2-currency-param.md  # 2.0 스캔 요청 파라미터 계약 변경분
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/scan/
├── ScanV2Controller.kt   # [수정] @RequestParam(required = false) currency: String? 추가,
│                         #        CurrencyCode 확정(잘못된 값 → BusinessException(INVALID_CURRENCY_CODE))
│                         #        후 서비스에 전달 — 검증은 요청 경계 소유(헌법 V)
├── ScanV2Api.kt          # [수정] currency 파라미터 swagger @Parameter 문서 (인터페이스는 문서만 — 규약)
├── ScanService.kt        # [수정] scanMenuBoardImageV2/scan 에 requestedCurrency: CurrencyCode? 추가,
│                         #        통화 결정 requestedCurrency ?: member.profile.currency
├── ScanV2Response.kt     # [수정] currency 필드 @Schema 설명 갱신 (파라미터 우선·프로필 fallback)
├── ScanResult.kt         # [불변] currency: CurrencyCode? 이미 존재
├── ScanController.kt     # [불변] 1.0 — currency 파라미터·응답 없음
└── ScanLangRequest.kt    # [불변] lang 전용 홀더 유지 (research R1)

api/src/test/kotlin/com/kbap/api/scan/
└── ScanControllerTest.kt # [수정] 파라미터 우선·잘못된 값 400·미전달 fallback 시나리오 추가

common/                   # [불변] CurrencyCode·ErrorCode.INVALID_CURRENCY_CODE 재사용
```

**Structure Decision**: 변경을 `:api` 의 scan 기능 패키지 안에 가둔다. 검증(raw → `CurrencyCode`)은 컨트롤러가, 우선순위 결정(파라미터 ?: 프로필)은 서비스가 소유한다 — 계층별 책임이 헌법 V(경계 검증)와 기존 `LanguageCode.from` 패턴에 정렬된다. 비회원 스캔이 도입돼 `member` 가 optional 이 되어도 파라미터 경로는 회원 의존이 없어 그대로 동작한다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
