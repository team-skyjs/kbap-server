# Implementation Plan: 스프링 모듈 구조 다이어트 — api·batch·common 3모듈로 통합

**Branch**: `kb-244-module-diet` | **Date**: 2026-07-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-244-module-diet/spec.md`

## Summary

16개 Gradle 모듈(core + 도메인 8종 + application + infra 4종 + 부트앱 2종)을 7개(`:common`·`:app:api`·
`:app:batch` + `:infra:{llm,auth,redis,storage}`)로 통합한다. 순수 구조 변경 — 기능·API·DB 스키마·패키지명
불변, 파일 이동과 빌드 배선만 바꾼다. common 배치 기준은 "api 밖(배치·인프라 어댑터)이 컴파일 의존하는가"
하나다. 집행은 PR 2개로 나눈다(사용자 지시): **PR #1** 공유 코드 common 추출·재배선 → **PR #2** api 전용
코드 흡수·모듈 제거·buildSrc 축소·문서/헌법 갱신. 각 태스크는 그린 빌드로 커밋한다. 세부 결정과 근거는
[research.md](research.md), 태스크 분해는 [tasks.md](tasks.md).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(:infra:llm), Gradle(Kotlin DSL) + buildSrc 컨벤션 플러그인

**Storage**: MySQL(Flyway, 스키마 owner=api) + Redis — **이 작업에서 변경 없음**

**Testing**: Kotest BehaviorSpec + JUnit 플랫폼, 통합 테스트 MySQL Testcontainers(:core→:common testFixtures), ArchUnit(경계)

**Target Platform**: 리눅스 서버(bootJar 2종 — api·batch)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스의 구조 리팩터링(코드 이동 — 신규 기능 없음)

**Performance Goals**: 해당 없음(런타임 동작 불변). 부수 효과로 모듈 감소에 따른 빌드 구성 단순화

**Constraints**: 전 단계 그린 빌드 유지(전체 테스트 통과), 패키지명 불변, 기존 테스트 의미 변경 금지,
Flyway 소유권(api)·batch flyway off 유지, 인프라 모듈 4종 유지(사용자 지시)

**Scale/Scope**: 모듈 16→7. 이동 대상: core·도메인 8종·application 의 전 소스(src/main + src/test),
빌드 파일 ~12개, buildSrc 아키타입 1종 폐기·1종 신설, ModuleBoundaryTest 규칙 보강, 헌법 v6.0.0 개정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS(조건부) | 신규 로직 없음 — 기존 테스트 슈트가 회귀 안전망(이동 후 그대로 통과해야 함). 유일한 신규 검증인 ArchUnit 도메인 간 방향 규칙은 테스트 먼저 추가(Red: 규칙만 넣고 위반 사례로 Red 확인이 불가한 순수 추가 규칙이므로, "기존 코드로 Green·위반 코드 샘플로 Red" 를 규칙 작성 시점에 확인) |
| II. Bounded Contexts | **위반(정당화·개정 동반)** | "도메인은 컨텍스트별 **모듈**로 둔다" 문언과 충돌. 컨텍스트 경계 자체는 패키지(`com.kbap.domain.<ctx>`) + ArchUnit 으로 유지 — 취지 보존, 수단 변경. Decision 6 의 헌법 개정(MAJOR) 을 구현과 동시 반영 |
| III. Layered Dependency Direction | **위반(정당화·개정 동반)** | 모듈 그래프 서술(부트앱→application→도메인→core)이 부트앱→common, infra→common 으로 바뀜. 패키지 수준 방향(app→application→domain→core, domain→상위 금지)은 ArchUnit 이 계속 강제. 기존 `:common`(공유 계약, jpa 비의존) 서술도 재정의 필요 |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델·리포지토리 public·명시적 트랜잭션·JPA 연관 금지 — 전부 코드 수준 규약이라 모듈 이동과 무관. "소유 도메인 **모듈** 안에" 문언만 "소유 도메인 **패키지** 안에" 로 개정 시 정합화 |
| V. Language Policy | PASS | 무관(콘텐츠·언어 동작 불변) |

**게이트 판정**: 위반 2건은 이 기능의 목적 그 자체(모듈 구조 재정의)이며, 선례(KB-134 → v3.0.0,
KB-220 → v5.0.0)대로 **헌법 개정을 구현에 포함**해 해소한다. Complexity Tracking 에 기록. → 진행 가능.

**Post-Phase 1 재점검**: 설계 산출물(모듈 매트릭스·재배선)이 취지 보존을 구체화함 — 판정 변화 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-244-module-diet/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 모듈 인벤토리 실측 + 결정 6건
├── data-model.md        # Phase 1 — 데이터 모델 변경 없음 명시
├── quickstart.md        # Phase 1 — 단계별 검증 명령
└── tasks.md             # Phase 2 (/speckit-tasks 가 생성)
```

(contracts/ 없음 — 외부 인터페이스(API 경로·응답·DB 스키마) 무변경이라 계약 산출물이 없다.)

### Source Code (repository root)

```text
common/                          # 신설 — 공유 코드 (구 core + food·member·avoidance + seam)
├── build.gradle.kts             # kbap.common-conventions (+ core 의 testFixtures·compileOnly 승계)
└── src/{main,test,testFixtures}/kotlin/com/kbap/
    ├── core/                    # 패키지 불변 — BaseEntity·ErrorCode·seam(ScannedNameInterpreter·StorageObjectStore)·LanguageCode
    ├── domain/{food,member,avoidance}/   # 공유 도메인 — 엔티티·리포지토리·도메인 서비스
    └── application/             # seam 인터페이스·dto 만 (TokenIssuer·TokenParser·SocialTokenVerifier·RefreshTokenStore·PresignedUploadPort)

app/api/                         # 유지 — api 전용 코드 흡수
└── src/{main,test}/kotlin/com/kbap/
    ├── KbapApiApplication.kt    # 불변 (com.kbap 루트)
    ├── app/api/                 # 컨트롤러·API DTO·config — 불변
    ├── application/             # ApplicationService(Home·Auth) — :application 에서 이동
    └── domain/{scan,bookmark,image,metering}/  # api 전용 도메인 — 이동

app/batch/                       # 유지 — 의존만 :domain:food·:domain:avoidance → :common 재배선
infra/{llm,auth,redis,storage}/  # 유지 — 의존만 :core/:application/:domain:member → :common 재배선
buildSrc/                        # kbap.domain-conventions 폐기 → kbap.common-conventions 신설, 나머지 유지
settings.gradle.kts              # 16 → 7 모듈
(삭제: core/, domain/ 전체(8종+research 잔존 디렉터리), application/)
```

**Structure Decision**: 모듈 경계는 "실행 단위(api·batch) + 공유(common) + 외부 시스템(infra 4종)" 로만
긋고, 도메인 경계는 패키지(`com.kbap.domain.<ctx>`) + ArchUnit 으로 유지한다. 목적지 결정 기준과 모듈별
매트릭스는 research.md Decision 1·2.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 II 문언(컨텍스트별 모듈) 위반 | 도메인별 모듈이 팀 규모 대비 관리 비용만 유발(KB-244 배경) — 모듈 통합이 기능 목적 | "현행 유지"는 스펙이 부정한 상태. 취지(컨텍스트 격리)는 패키지+ArchUnit 으로 보존하고 헌법 v6.0.0 개정 동반 |
| 원칙 III 모듈 그래프 서술 위반 | 모듈 축소로 그래프 자체가 재정의됨 | 패키지 수준 의존 방향은 ArchUnit 이 동일하게 강제 — 개정으로 문언 정합화 |
