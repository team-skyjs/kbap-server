# Implementation Plan: dev 로그 출력을 prod 와 동일하게

**Branch**: `kb-652-dev-log-prod-parity` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md) | **Jira**: [KB-652](https://simhani1.atlassian.net/browse/KB-652)

**Input**: Feature specification from `/specs/kb-652-dev-log-prod-parity/spec.md`

## Summary

dev 프로필의 로그 설정을 각 앱의 prod 프로필과 같게 맞춘다. api dev 에 `logging.structured.format.console: ecs` 를 넣고 `spring.jpa.show-sql` 을 false 로, batch dev 는 `show-sql` 만 false 로 바꾼다. 기존 `StructuredConsoleLoggingTest` 의 프로필 목록에 dev 를 추가하는 것 외에 새 테스트는 없다. 확인은 로컬에서 dev 프로필로 띄워 JSON 줄·SQL 미출력을 보고, 머지 후 dev CloudWatch 에서 `requestId` 필드 검색으로 끝낸다. 1만 동접 램프업 부하 테스트(별도 작업)의 전제다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21) — 코드 변경은 테스트 한 줄뿐

**Primary Dependencies**: Spring Boot 4.1 내장 구조화 로깅(`logging.structured.format.console=ecs`), Hibernate `show-sql`

**Storage**: 변경 없음

**Testing**: 기존 `StructuredConsoleLoggingTest`(Kotest BehaviorSpec, Spring 컨텍스트 없이 yml·인코더 직접 검사) 프로필 목록 확장. 신규 테스트 없음(research D3)

**Target Platform**: dev ECS(api·batch) — develop 푸시 시 자동 배포. 로그는 `awslogs` → CloudWatch(prod 와 같은 Terraform 모듈)

**Project Type**: 설정 변경(yml 2파일) + 테스트 1줄

**Performance Goals**: 해당 없음 — 이 작업은 부하 테스트 측정치의 대표성을 확보하는 전제 작업

**Constraints**: local·staging·prod 프로필 yml 무변경(FR-004·005) · 로그 남기는 코드·메시지 무변경(FR-007) · 배치는 텍스트 유지(prod 와 동일)

**Scale/Scope**: 수정 파일 3개 — `api/src/main/resources/application-dev.yml`, `batch/src/main/resources/application-dev.yml`, `api/src/test/kotlin/com/kbap/api/core/logging/StructuredConsoleLoggingTest.kt`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 새 동작이 아니라 프레임워크 토글이다. 기존 프로퍼티 검사 테스트에 dev 를 추가하면 **먼저 실패(dev yml 에 키 없음)** 하고 yml 수정 후 통과한다 — 한 줄짜리 Red→Green 이 성립한다. 도메인 테스트를 새로 만들지 않는 것은 KB-380·KB-411 선례. |
| II. Bounded Contexts | PASS | 도메인 코드 무변경. |
| III. Layered Dependency Direction | PASS | 의존 변경 없음. |
| IV. Persistence Ownership | PASS | 영속 코드·스키마 무변경. `show-sql` 은 Hibernate 출력 토글이다. |
| V. Language Policy | PASS | 무관. |

**게이트 판정**: PASS. Complexity Tracking 기록 사항 없음.

**Phase 1 재검토**: 설계 후 변화 없음. PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-652-dev-log-prod-parity/
├── spec.md              # /speckit-specify 출력
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 조사 결과 + 결정 D1~D5
├── data-model.md        # Phase 1 — 프로필별 설정 전후 표, 로그 줄 필드
├── quickstart.md        # Phase 1 — 정적 검사·테스트·로컬 실노출·dev CloudWatch 확인
└── tasks.md             # /speckit-tasks 출력
```

`contracts/` 는 만들지 않는다 — 외부 인터페이스 변경이 없다. 로그 줄의 필드 집합은 data-model 에 적었다.

### Source Code (repository root)

```text
api/src/main/resources/
└── application-dev.yml                 # + logging.structured.format.console: ecs (prod 와 같은 블록·주석), show-sql: true → false
batch/src/main/resources/
└── application-dev.yml                 # show-sql: true → false
api/src/test/kotlin/com/kbap/api/core/logging/
└── StructuredConsoleLoggingTest.kt     # listOf("staging","prod") → listOf("dev","staging","prod"), given 설명 갱신
```

**Structure Decision**: 프로필 yml 과 그 검사 테스트만 만진다. 베이스 yml·logback-spring.xml·필터 코드는 건드리지 않는다(research D1).

## 구현 순서 (tasks 생성 시 기준)

1. `StructuredConsoleLoggingTest` 프로필 목록에 `dev` 추가 → `./gradlew :api:test` 로 **실패 확인**(Red).
2. api `application-dev.yml` 에 structured 블록 추가 + `show-sql: false` → 테스트 통과(Green).
3. batch `application-dev.yml` `show-sql: false`.
4. quickstart §1 정적 검사·§2 테스트.
5. quickstart §3 로컬 dev 프로필 기동으로 JSON 줄·`requestId`·SQL 0건 확인.
6. 커밋·draft PR(develop). 머지 후 quickstart §4 dev CloudWatch 확인 → Jira DoD 4번째 항목 체크.

## Complexity Tracking

위반 없음 — 기록 사항 없음.

## 범위 밖 보고

- 배치 베이스 yml 의 `logging.level.com.kbap.infra.llm.provider: DEBUG` 는 존재하지 않는 패키지를 가리키는 죽은 설정이다(research D5). 전 프로필 공통이라 이 작업엔 영향 없음. 별도 정리 후보.
