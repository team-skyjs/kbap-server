# Implementation Plan: 아키텍처 단순화 — persistence 모듈 해체·port 폐기·JPA 연관관계 제거

**Branch**: `kb-134-architecture-simplification` | **Date**: 2026-07-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-134-architecture-simplification/spec.md`

## Summary

클린아키텍처 ports & adapters 를 폐기하고 구조를 단순화하는 순수 리팩토링(KB-134, KB-101 흡수). `:infra:persistence` 를 해체해 모든 JPA 영속 코드를 각 도메인 모듈로 옮기고, 리포지토리 port·어댑터를 삭제하며, 각 도메인은 **도메인 서비스 하나를 public 창구**로 두고 엔티티·Spring Data 리포지토리는 **Kotlin `internal`** 로 감춘다(Gradle 모듈 = 컴파일 단위라 컴파일러가 경계 강제). JPA 연관관계를 전면 제거하고 참조는 **id 값 클래스(FoodId·MemberId) + AttributeConverter** 로 든다(FK 제약은 스키마가 강제 — 이미 전부 존재). 모듈 명칭을 `core/` → `domain/`, `:core:kernel` → `:core` 로 개편하고 BaseEntity·EntityStatus 를 `:core` 로 옮긴다. 죽은 MongoDB 잔재를 제거한다. 유스케이스의 페이크 port 단위 테스트는 **통합 테스트(도메인 서비스 + MySQL Testcontainers, 기존 MockMvc 컨트롤러 테스트)로 흡수**한다(mockk 미도입 — 사용자 결정). API 계약·동작·DB 스키마는 무변경이다.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·actuator·data-jpa·data-redis), Flyway(+mysql), springdoc, Spring AI 2.0(`:infra:llm` — 무변경), jjwt·firebase-admin(application — 무변경). **제거**: `spring-boot-starter-data-mongodb` 카탈로그 항목(빌드 파일 사용처 0건 — 카탈로그·yml·compose 잔재만 존재)

**Storage**: MySQL 8.4 (스키마 무변경 — FK 제약은 기존 마이그레이션에 전부 존재: `fk_fas_food`·`fk_fas_substance`·`fk_scan_history_member`·`fk_scan_history_food`). Redis(refresh token — 무변경). MongoDB 잔재 제거

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers(공유 컨테이너 설정은 `:core` testFixtures 로 이동) + MockMvc + ArchUnit(전면 재작성). 페이크 port 단위 테스트 → 통합 테스트 흡수

**Target Platform**: Linux server (bootJar 2개: `:app:api`·`:app:batch`)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 구조 자체가 이번 변경 대상

**Performance Goals**: 해당 없음(동작 무변경). 지연 로딩 제거로 N+1 구조적 차단은 부수 효과

**Constraints**: 기존 API 요청·응답 계약 100% 동일(FR-014), DB 스키마 무변경, 기존 마이그레이션 파일 이동·리네임 금지, `:infra:llm`·`:common` 무변경

**Scale/Scope**: 도메인 6종(food·member·avoidance·scan·research·review) + persistence 34파일 이동/삭제 + port 6종 폐기 + 컨벤션 플러그인 2종 개정 + 전 모듈 패키지 리네임 + 테스트 재배치. 유일한 JPA 연관관계는 `FoodJpaEntity` 의 `@OneToMany`(→ 명시적 자식 관리로 전환) 1건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**이 기능은 헌법 v2.3.1 의 원칙 III·IV 를 의도적으로 대체하는 작업이다.** 현행 헌법은 `:infra:persistence` 분리·port-only 사용·도메인 ORM-free 를 명령하는데, KB-134 의 목적 자체가 그 구조의 폐기다. Governance 절차(문서화된 개정 + 버전 증가)에 따라 **헌법 개정을 이 기능의 첫 산출물**로 수행한다 — 원칙 재정의이므로 MAJOR(v3.0.0). 개정 전까지는 아래 표의 "개정 후" 기준으로 게이트를 평가한다.

| 원칙 | 현행 대비 | 처리 |
|------|----------|------|
| I. Test-First | **준수** — 새 ArchUnit 규칙(새 구조 기대)을 먼저 작성해 Red 확인 → 구조 이동으로 Green. 기존 시나리오 테스트는 재배치 후 전부 green 유지 | 게이트 통과 |
| II. Bounded Contexts | **준수(문구만 개정)** — 도메인 모듈 간 직접 의존 금지·id/코드 참조·Aggregate Root 규칙 모두 유지. 모듈 표기(`:core:*`→`:domain:*`)와 공유 id 값 클래스의 `:core` 배치만 반영 | 문구 개정 |
| III. Layered Dependency Direction | **의도적 대체** — port-only·runtimeOnly 조립 폐기. 새 방향: 부트앱 → application → 도메인 모듈 → `:core`, 도메인 서비스가 public 창구, 영속은 internal | 개정 (MAJOR) |
| IV. Persistence Encapsulation | **의도적 대체** — "JPA 는 :infra:persistence 에" → "JPA 는 소유 도메인 모듈 안에 internal 로". 캡슐화 목적은 동일하고 강제 수단이 리뷰+ArchUnit → **컴파일러(internal)** +ArchUnit 으로 강해진다 | 개정 (MAJOR) |
| V. Domain Content Language | **무관** — 콘텐츠·번역 정책 비접촉 | 게이트 통과 |
| Additional Constraints | MongoDB 스택 표기 제거, 모듈러 모놀리스 서술 갱신 | 문구 개정 |

**Post-design re-check**: Phase 1 설계는 개정 후 원칙(도메인 서비스 창구·internal 영속·연관관계 금지·컨텍스트 격리 유지)을 모두 만족한다. 위반 없음 — Complexity Tracking 은 헌법 개정 자체의 정당화만 기록한다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-134-architecture-simplification/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 12건(이관 방식·id 값 클래스·테스트 전략·헌법 개정 등)
├── data-model.md        # Phase 1 — 모듈 매핑·파일 이동표·연관관계 제거 상세
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/           # Phase 1 — API 계약 무변경 선언 + 회귀 고정 방법
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
core/                          # ← 구 core/kernel (:core:kernel → :core), 패키지 com.meogo.core
└── src/main/kotlin/com/meogo/core/
    ├── (기존 kernel 코드: error·lang·menu·risk·scan(ScannedNameInterpreter — LLM port 유지)·stereotype)
    ├── persistence/           # BaseEntity·EntityStatus (persistence 에서 이동)
    └── id/                    # FoodId·MemberId 값 클래스 + IdConverter + 타입별 @Converter
└── src/testFixtures/          # MySqlContainerConfig·RedisContainerConfig (persistence testFixtures 에서 이동)

domain/                        # ← 구 core/ 컨테이너, 패키지 com.meogo.domain.<도메인>
├── food/                      # 모델·FoodService(public) + FoodJpaEntity·FoodJpaRepository·FoodAvoidanceSubstanceJpaEntity(internal)
├── member/                    # 모델·MemberService(public)·RefreshTokenStore(Redis 구체, public) + 엔티티·리포지토리(internal)
├── avoidance/                 # 모델·AvoidanceSubstanceService(public) + 엔티티·리포지토리·Reconstitutor(internal)
├── scan/                      # ScanHistory·ScanHistoryService(public) + 엔티티·리포지토리(internal)
├── research/                  # 순수 로직(영속 없음) — 코드 무변경, 위치·패키지만 개편
└── review/                    # placeholder — 위치만 개편

application/client/            # 유스케이스가 port 대신 도메인 서비스 조합 (패키지 무변경)
infra/llm/                     # 무변경 (import 경로만 갱신)
app/api/                       # runtimeOnly persistence 조립 제거, ArchUnit 재작성, Flyway owner 유지
app/batch/                     # 잡이 FoodService 등 도메인 서비스 직접 사용
common/                        # 무변경
buildSrc/                      # meogo.domain-conventions 에 spring·jpa 추가, :core 용 구성 정리
```

**Structure Decision**: 삭제 — `infra/persistence/`(34파일: main 21 + test 11 + testFixtures 2 전부 이동 또는 폐기). 리네임 — `core/<도메인>` → `domain/<도메인>`(패키지 `com.meogo.core.<d>` → `com.meogo.domain.<d>`), `core/kernel` → `core/`(패키지 `com.meogo.core.kernel` → `com.meogo.core`). 이동·삭제·전환의 파일 단위 상세는 [data-model.md](data-model.md).

## Complexity Tracking

> Constitution Check 위반 정당화만 기록

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 헌법 원칙 III·IV 대체 (MAJOR 개정 v3.0.0) | ports & adapters 의 조각 수·폴더 뎁스 비용이 얻는 것(계층 교체 가능성)보다 크다는 KB-134 의 판단. 경계는 port 없이 Kotlin internal + 도메인 서비스 창구로 컴파일러가 더 강하게 강제 | "헌법 유지 + 부분 단순화"는 기각 — port·어댑터·별도 모듈이라는 비용 구조가 그대로 남아 이슈의 목적을 달성하지 못함. 리뷰 기능(KB-128·129·131) 전에 하지 않으면 리뷰 영속 코드를 두 번 씀 |
| `:core` 가 jakarta.persistence·hibernate 애너테이션에 compileOnly 의존 | BaseEntity(@MappedSuperclass·@SQLRestriction)·EntityStatus·IdConverter 를 전 도메인이 상속/사용 — 공유 최하층은 `:core` 뿐 | 도메인별 BaseEntity 복제는 6벌 중복. 별도 `:persistence-support` 모듈 신설은 모듈 수 줄이려는 이번 취지와 역행 |
