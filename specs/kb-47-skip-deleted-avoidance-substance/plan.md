# Implementation Plan: 음식 상세조회 — 삭제된 기피 성분 skip 처리(조회 장애 내성)

**Branch**: `kb-47-skip-deleted-avoidance-substance` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-47-skip-deleted-avoidance-substance/spec.md`

## Summary

음식 상세조회(`GetFoodDetailUseCase`)는 음식이 참조하는 기피 성분 코드로 카탈로그(`AvoidanceSubstanceRepository.findByCodes`)를 조회해 성분 표시명·위험도를 조립한다. 카탈로그는 `@SQLRestriction("status='ACTIVE'")`로 ACTIVE 만 읽으므로, 소프트 삭제된(status='DELETED') 성분은 조회에서 빠지고 `catalog[code] == null` 이 되어 현재 `IllegalStateException` → 상세조회 전체 500 실패가 발생한다.

**기술 접근**: `GetFoodDetailUseCase.getDetail()` 에서 참조 성분을 `partition { code in catalog }` 으로 **존재/부재 두 갈래로 가른다**. 부재(삭제) 성분은 예외 대신 **skip**하며 각각 **WARN 로그**(`foodId`·`substanceCode`)를 남기고, 존재 성분만 `map { catalog.getValue(code) … }` 으로 응답을 조립한다(`null` 미사용 — skip+로그와 조립의 관심사 분리). 응답 계약·정렬·위험도 조립 로직은 그대로 두어(변경 표면 최소) 회귀를 배제한다. 단일 파일(유스케이스) 수정 + 테스트 추가. **DB 스키마·마이그레이션·엔티티·API DTO 변경 없음.**

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (spring-tx, slf4j), 변경 대상 모듈은 `:application:client`

**Storage**: MySQL(prod) / H2(test) — **본 변경으로 접근 방식·스키마 불변**. 카탈로그 소프트 삭제(`status='ACTIVE'` SQLRestriction)는 기존 정책 그대로 전제

**Testing**: Kotest `BehaviorSpec` (given/when/then 한국어). 유스케이스 단위 테스트(Fake 리포지토리) + 로그 캡처(Logback `ListAppender`)

**Target Platform**: Linux server (web bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 백엔드 (ADR-0008)

**Performance Goals**: 추가 쿼리·N+1 없음 — 기존과 동일한 단일 `findByCodes` 호출 유지. skip 은 인메모리 필터링

**Constraints**: 응답 계약(`ingredients[]` 필드 구조·정렬) 동결. 안전 민감 도메인 — 성분 skip 은 조용히 넘기지 않고 WARN 기록 필수

**Scale/Scope**: 단일 유스케이스 메서드 1개 수정 + 테스트. 신규 모듈·마이그레이션 없음

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ PASS | 구현 전 실패 테스트를 먼저 작성한다: (a) 성분 1개 소프트 삭제 → skip 후 나머지 조립, (b) 전부 삭제 → 빈 목록, (c) 정상(회귀), (d) skip 시 WARN 로그. Red 확인 후 Green. |
| **II. Bounded Contexts** | ✅ PASS | food·avoidance 조합은 이미 `:application:client` 에서만 이뤄진다. 도메인 간 직접 의존 신규 추가 없음. 성분은 코드(`AvoidanceSubstanceCode`)로만 참조. |
| **III. Layered Dependency** | ✅ PASS | 변경은 `:application:client` 내부. 리포지토리는 port(`AvoidanceSubstanceRepository`) 로만 사용. 하위 구현 세부 의존 없음. |
| **IV. Persistence Encapsulation** | ✅ PASS | JPA/엔티티/DTO 변경 없음. 유스케이스는 도메인 객체·port 만 다룬다. |
| **V. Domain Content Language Policy** | ✅ PASS | 언어 폴백·번역 로직 불변. 성분 표시명 조립 시 기존 `displayName(lang)` 그대로. |
| **추가 제약** (트랜잭션·모델 비노출) | ✅ PASS | 외부 호출 없음. 트랜잭션 경계 불변(`@Transactional(readOnly=true)`). 도메인/영속 모델 API 미노출 유지. |

**게이트 결과**: 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-47-skip-deleted-avoidance-substance/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── food-detail-get.md
├── checklists/
│   └── requirements.md  # (from /speckit-specify)
└── tasks.md             # /speckit-tasks output (NOT created here)
```

### Source Code (repository root)

```text
application/client/src/main/kotlin/com/meogo/application/client/food/usecase/
└── GetFoodDetailUseCase.kt          # [수정] catalog miss → skip + WARN 로그

application/client/src/test/kotlin/com/meogo/application/client/food/usecase/
└── GetFoodDetailUseCaseTest.kt      # [수정] skip/전부삭제/회귀/로그 시나리오 추가
```

**Structure Decision**: 변경은 유스케이스(`:application:client`) 단일 계층에 국한한다. 장애 원인이 "참조는 있으나 카탈로그에 없는" **조회 시점 조합 불일치**이고, 조합 책임이 헌법 원칙 II 상 application 계층에 있기 때문이다. 도메인(`:core:food`·`:core:avoidance`)·영속(`:infra:persistence`)·web(`:app:api`)·DB 는 건드리지 않는다. 응답 DTO(`FoodDetailResponse`)도 그대로 — skip 은 조립 단계에서 흡수되어 계약에 드러나지 않는다.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성 불필요.
