# Implementation Plan: 스캔 응답에 회원 통화 환산 정보 제공

**Branch**: `kb-323-scan-currency-conversion` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-323-scan-currency-conversion/spec.md`

## Summary

2.0 스캔 성공 응답(`ScanV2Response`)에 회원 프로필 통화의 **코드 + 환율 값(1단위당 원화, `krwPerUnit`)** 을 응답 수준 추가 필드로 내려준다. 환산 계산·반올림은 전부 클라이언트 소관이며, 서버는 항목별 환산 금액을 만들지 않는다. 환율 출처는 기존 `CurrencyCode.krwPerUnit` 고정 스냅샷(회원 통화 기능이 이미 보유)이고 외부 조회·캐시 인프라를 추가하지 않는다. 통화 미설정 회원은 해당 필드만 null 인 채 스캔이 성공한다. 1.0 스캔 응답(`ScanResponse`)은 변경하지 않는다.

핵심 변경 3파일: `ScanService.scan()` 이 이미 호출하고 결과를 버리던 `memberService.getMember(memberId)` 의 반환값에서 `member.profile.currency` 를 꺼내 `ScanResult` 에 싣고, `ScanV2Response.from()` 이 이를 중첩 객체로 노출한다. DB·마이그레이션·인프라 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi (Swagger 스키마 애너테이션)

**Storage**: 변경 없음 — 신규 테이블·컬럼·Flyway 마이그레이션 없음. 회원 통화 컬럼(`member.currency`, KB-322)과 `CurrencyCode` enum 스냅샷을 읽기만 한다.

**Testing**: Kotest BehaviorSpec (given/when/then 한국어) + `@SpringBootTest`/MockMvc 통합 테스트, MySQL Testcontainers

**Target Platform**: Linux 서버 (api bootJar)

**Project Type**: web-service (`:api` 모듈 단독 변경)

**Performance Goals**: 스캔 응답 시간 불변 — 추가 DB 조회·외부 호출 0건 (기존 `getMember` 호출 재사용)

**Constraints**: 기존 2.0 응답 필드 형태·의미 불변(additive only), 1.0 응답 불변, 스캔 경로에 외부 환율 조회 금지

**Scale/Scope**: `:api` 모듈 3개 소스 파일 수정 + 테스트. `:common`·`:batch`·`:infra:*` 무변경

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 실패 테스트(통화 포함·미설정 null·1.0 불변) 선작성 → Red 확인 → 최소 구현. tasks 단계에서 test-writer → implementer 순서 고정 |
| II. Bounded Contexts | PASS | 신규 도메인 간 의존 없음. `CurrencyCode` 는 공유 vocabulary(`common.domain` 루트)로 이미 존재, scan 기능 패키지(`com.kbap.api.scan`)가 `MemberService` 를 이미 소비 중(기존 허용 방향). `ModuleBoundaryTest` 허용 맵 수정 불필요 |
| III. Layered Dependency Direction | PASS | `com.kbap.api.scan` → `com.kbap.common.domain` 기존 방향 그대로. infra seam 무관 |
| IV. Persistence Ownership | PASS | 영속 변경 없음. 리포지토리·엔티티·트랜잭션 경계 손대지 않음 |
| V. Domain Content Language Policy | PASS | 음식 콘텐츠·lang 정책 무관. 통화 코드는 ISO 4217 식별자이지 번역 콘텐츠가 아님 |
| Additional Constraints | PASS | 도메인/영속 모델을 응답으로 직접 노출하지 않음 — `CurrencyCode` enum 은 `ScanV2Response` 내부 DTO(`CurrencyResponse`)로 변환해 노출 |

**Post-Phase 1 재평가**: PASS 유지 — 설계 산출물에서 새 위반 요소 없음 (Complexity Tracking 불필요).

## Project Structure

### Documentation (this feature)

```text
specs/kb-323-scan-currency-conversion/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── scan-v2-currency.md   # 2.0 스캔 응답 계약 변경분
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/scan/
├── ScanService.kt        # [수정] getMember 반환값 재사용 → ScanResult 에 currency 전달
├── ScanResult.kt         # [수정] currency: CurrencyCode? 필드 추가
├── ScanV2Response.kt     # [수정] currency: CurrencyResponse? 필드 + from() 매핑 + Swagger 스키마
├── ScanResponse.kt       # [불변] 1.0 응답 — currency 를 읽지 않음
├── ScanController.kt     # [불변]
├── ScanV2Controller.kt   # [불변]
└── ScanV2Api.kt          # [불변] 응답 스키마는 ScanV2Response 애너테이션이 담당

api/src/test/kotlin/com/kbap/api/scan/
└── ScanControllerTest.kt # [수정] v2 통화 포함·미설정 null·1.0 불변 시나리오 추가

common/                   # [불변] CurrencyCode(krwPerUnit 스냅샷)·MemberProfile.currency 재사용
```

**Structure Decision**: `:api` 의 scan 기능 패키지(`com.kbap.api.scan`) 안에서만 변경한다. 통화 코드·환율 스냅샷·회원 프로필 접근은 전부 `:common` 의 기존 공개 API(`CurrencyCode`, `MemberService.getMember`, `Member.profile`)를 그대로 소비하므로 `:common` 은 한 줄도 바뀌지 않는다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
