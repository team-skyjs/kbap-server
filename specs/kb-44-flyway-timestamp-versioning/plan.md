# Implementation Plan: Flyway 점 구분 timestamp 버전 규칙 전환

**Branch**: `kb-44-flyway-timestamp-versioning` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-44-flyway-timestamp-versioning/spec.md`

## Summary

신규 Flyway 마이그레이션 버전을 정수(`V1`…`V10`)에서 **점 구분 timestamp**(`Vyyyy.MM.dd.HH.mm.ss__description.sql`, Flyway 공식 유효 예시 `2013.01.15.11.35.56` 형)로 전환한다. 병렬 브랜치 머지 시 버전 번호 충돌을 제거하는 것이 목적이다. 생성 시각 기반이라 뒤늦게 머지된 과거 버전이 **out-of-order** 로 적용되므로, `spring.flyway.out-of-order=true` 를 켜고 **마이그레이션 순서-독립 작성 원칙**을 컨벤션으로 강제한다. 산출물은 **컨벤션 문서(CLAUDE.md + meogo-conventions.md) + `application.yml` 1줄 + 로컬 docker MySQL 검증**이며, 기존 정수 파일은 불변.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain (변경 없음 — 이 작업은 Kotlin 코드 무변경)

**Primary Dependencies**: Flyway (`flyway-core` + `flyway-mysql`), Spring Boot 4.1 Flyway autoconfig(`spring-boot-flyway`)

**Storage**: MySQL(prod/local docker `meogo-mysql`) — 스키마 owner `:app:api`. 테스트는 H2 + flyway off(마이그레이션 미실행)

**Testing**: 자동화 테스트 없음(아래 Constitution Check 참조) — 검증은 로컬 docker MySQL 부팅 실측(quickstart.md)

**Target Platform**: Linux server (Spring Boot bootJar `:app:api`)

**Project Type**: 모듈러 모놀리스 백엔드 — 본 작업은 **문서 + 설정** 변경(무 도메인 코드)

**Performance Goals**: N/A (버전 규칙 변경, 런타임 성능 무관)

**Constraints**: 기존 `V1`~`V10` 파일·checksum·history 불변. out-of-order 는 마이그레이션 순서-독립 전제 위에서만 안전. 설정은 전 실행 프로필 일관 적용.

**Scale/Scope**: 파일 3개 내외 수정 — `CLAUDE.md`, `docs/architecture/meogo-conventions.md`, `app/api/src/main/resources/application.yml`. 신규 SQL 마이그레이션 생성 없음(규칙만 도입).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ⚠️ 정당화된 예외 | 프로덕션 도메인 코드 변경이 없다(문서 + `application.yml` 설정 1줄). Flyway 동작은 테스트 스위트에서 검증 불가 — 프로젝트 관례상 테스트는 H2 + **flyway off** 라 마이그레이션이 아예 실행되지 않는다(알려진 갭). 따라서 실패 테스트 우선 작성이 성립하지 않으며, 검증은 **로컬 docker MySQL 부팅 실측**(US4/quickstart.md)으로 대체한다. Complexity Tracking 에 기록. |
| **II. Bounded Contexts** | ✅ 해당 없음 | 도메인 모듈 무변경. 컨텍스트 간 결합 없음. |
| **III. Layered Dependency Direction** | ✅ 해당 없음 | 모듈 의존 그래프 무변경. |
| **IV. Persistence Encapsulation** | ✅ 부합 | JPA/엔티티/리포지토리 무변경. Flyway 는 이미 `:app:api`(스키마 owner) 소관 — 그 경계 안에서만 설정/규칙 조정. |
| **V. Domain Content Language Policy** | ✅ 해당 없음 | 콘텐츠/번역/언어 폴백 무관. |
| **Additional Constraints** (Flyway 마이그레이션) | ✅ 부합 | 스택 제약(Flyway 사용)과 일치. 외부 호출/트랜잭션·API 노출 항목 무관. |

**게이트 결과**: 통과(원칙 I 은 코드 무변경 문서·설정 작업이라 자동 테스트 부재를 정당화, 로컬 MySQL 실측으로 검증). 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-44-flyway-timestamp-versioning/
├── plan.md              # 이 파일
├── spec.md              # 기능 명세
├── research.md          # Phase 0 — Flyway 포맷·정렬·out-of-order 사실 정리 + 결정
├── data-model.md        # Phase 1 — 버전 파일명 문법 + 설정 키(형식적 모델)
├── quickstart.md        # Phase 1 — 로컬 docker MySQL 검증 런북
├── checklists/
│   └── requirements.md  # spec 품질 체크리스트(완료)
└── tasks.md             # /speckit-tasks 산출(이 명령이 만들지 않음)
```

> **contracts/ 생략**: 본 작업은 외부에 노출되는 인터페이스(API·CLI·라이브러리 계약)가 없는 **내부 개발 컨벤션 + 설정** 변경이다. plan 템플릿 지침("Skip if project is purely internal")에 따라 contracts 디렉터리를 만들지 않는다.

### Source Code (repository root) — 변경 대상

```text
CLAUDE.md
  └─ 컨벤션 섹션: Flyway 마이그레이션 버전 규칙(점 구분 timestamp) 짧은 규칙 + 금지 사례 + meogo-conventions 포인터

docs/architecture/meogo-conventions.md
  └─ Flyway 마이그레이션 버전 규칙 상세: 포맷 근거(공식 문서)·정수↔timestamp 공존·out-of-order·순서-독립 원칙

app/api/src/main/resources/application.yml
  └─ spring.flyway.out-of-order: true  (전 프로필 상속 위해 베이스 yml)

app/api/src/main/resources/db/migration/   (읽기 전용 — 절대 수정/리네임 금지)
  └─ V1__..sql … V10__..sql  (기존 정수 이력 보존)
```

**Structure Decision**: 신규 모듈·소스셋 없음. 컨벤션은 **CLAUDE.md(항상 로드되는 런타임 개발 가이드)에 짧은 강제 규칙**, **`docs/architecture/meogo-conventions.md`(상세 규범 — 헌법 Governance 가 지정한 상세 규범 위치)에 근거·설명**으로 이원화한다. 설정은 환경 무관 동작이라 프로필별 파일이 아닌 **베이스 `application.yml`** 에 둔다(테스트 override yml 은 flyway off 라 무영향).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 I(Test-First) 자동 테스트 부재 | 변경 대상이 문서 + `application.yml` 1줄이라 실패시킬 프로덕션 코드가 없음. Flyway 동작은 테스트 스위트(H2·flyway off)에서 실행조차 되지 않음 | 통합 테스트로 강제하려면 MySQL Testcontainers + flyway on 인프라를 새로 도입해야 함 — 규칙 1건 검증에 과도. 기존 관례(로컬 docker MySQL 실측)로 US4/quickstart 검증이 충분하고 저비용 |
