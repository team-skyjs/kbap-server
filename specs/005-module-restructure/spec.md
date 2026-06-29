# Feature Specification: 모듈러 모놀리스 재구조화

**Feature Branch**: `005-module-restructure`

**Created**: 2026-06-29

**Status**: Done (구현 완료 — 머지 대기)

**Input**: meogo-api 컨테이너 해체 → 공유 계층(core·application·infra)을 최상위로 올리고 두 부트앱(api·batch)이 도메인/영속을 직접 재사용하는 모듈러 모놀리스로 전환. 모듈 접두어 제거, 패키지 `com.meogo.<layer>` 정합, application 을 진입점별 분할.

> **성격**: 기능이 아니라 **아키텍처 리팩터**다. 결정 근거·상세는 **[ADR-0008](../../docs/adr/0008-modular-monolith-shared-domain.md)** 가 권위 문서다. 본 spec 은 SpecKit 사이클로 묶기 위한 요약·범위 선언이며, 004(회피·주의 성분 카탈로그)가 이 새 구조(특히 `:core:kernel` 공유 enum) 위에서 진행되도록 **선행 병합** 대상이다.

## 동기 (Why)

- 디커플드 batch(이벤트 전용)로는 알러지/회피 코드·도메인을 batch(LLM 프롬프트·조사 종합)와 공유할 수 없어 **중복 정의**가 강제됐다(안전 직결 데이터 드리프트 위험).
- DB 는 이미 공유였고(batch flyway off, owner=api) 코드 레벨만 디커플이라, 도메인/엔티티 공유가 더 일관적이다.
- 멀티모듈의 목적(재사용·중복 제거)을 코드 레벨에서 실현.

## 범위 (What)

- `meogo-api` 컨테이너 해체 → 최상위 `core`/`application`/`infra`/`app`/`common` 그룹.
- 모듈 접두어(`meogo-`) 제거, 패키지 `com.meogo.<layer>` 미러링.
- `app:batch` 가 `core:도메인`·`infra:persistence` 를 직접 의존(이벤트 전용 제약 폐기).
- `application` 진입점별 분할 — 현재 `:application:client`(나머지는 생길 때).
- 진입점 `MeogoApiApplication`을 `com.meogo` 루트로 이동(전 계층 스캔).
- 헌법/컨벤션/문서(CLAUDE.md·meogo-conventions·module-structure 등) 동기화.

## 범위 밖

- 신규 비즈니스 기능 없음(동작 불변 — 기존 테스트 전부 그린 유지).
- `:application:shared`/`:batch`/`:admin`, `:infra:external`(LLM) 은 필요 시 후속.
- ArchUnit 의존 규칙 갱신은 후속.

## 완료 기준 (Success Criteria)

- **SC-001**: `./gradlew build` 그린(컨텍스트 로드·MockMvc·H2 포함), 기존 테스트 동작 불변.
- **SC-002**: 잔여 stale 모듈경로/패키지 토큰 0(코드·문서).
- **SC-003**: ADR-0008 가 ADR-0001/0006 을 supersede 기록, CLAUDE.md·conventions 가 새 구조와 일치.
- **SC-004**: `app:batch` 가 공유 도메인/영속을 의존 가능(중복 정의 불요).

## 산출 (as-built)

커밋: 구조 이동(Step A) → 패키지 정리(Step B) → 문서 동기화 → application 분할 → Spring-free 서술 정정. 전 단계 빌드 그린.
