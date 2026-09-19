# Implementation Plan: Sentry 노이즈 차단 — 존재하지 않는 경로(404) 이벤트 미전송

**Branch**: `kb-510-ignore-no-resource-found` | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-510-ignore-no-resource-found/spec.md`

## Summary

매핑되지 않은 경로로 들어온 요청이 만드는 404(`NoResourceFoundException`)만 Sentry 로 보내지 않는다. 수단은 Sentry Spring Boot 스타터가 이미 제공하는 `sentry.ignored-exceptions-for-type` 설정 한 줄 — api `application.yml` 에 예외 클래스 하나를 등록한다. SDK 는 이 예외를 클라이언트 단계(이벤트 프로세서·전송 이전)에서 정확한 클래스 일치로 버리므로 `SentryRequestContextProcessor`·`GlobalExceptionHandler`·HTTP 응답은 무변경이다. 그 외 4xx(앱 에러 코드·405·415)·5xx 수집은 그대로다. 문서 `docs/observability/sentry.md` 의 "수집되지 않는 것" 에 기록한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain — 이번 변경에 Kotlin 소스 없음

**Primary Dependencies**: Spring Boot 4.1, `io.sentry:sentry-spring-boot-4-starter` 8.55.0(이미 도입, KB-508). 신규 의존 없음

**Storage**: N/A

**Testing**: 자동 테스트 없음 — 관측 부품 무테스트 결정(2026-09-09, KB-508)에 따라 dev 배포 후 콘솔 실이벤트로 검증. 로컬은 가짜 DSN + `sentry.debug` 로 SDK 의 drop 로그 확인(quickstart)

**Target Platform**: api 컨테이너(ECS dev → prod). batch 는 웹 경로가 없어 대상 밖

**Project Type**: web-service 설정 변경(yml 1줄 + 문서)

**Performance Goals**: N/A — 오히려 이벤트 전송이 줄어든다

**Constraints**: 4xx 수집 정책(KB-508) 유지 — 예외 한 종류만 제외. 이벤트 프로세서 코드 무수정(spec Assumptions)

**Scale/Scope**: 파일 2개(`api/src/main/resources/application.yml`, `docs/observability/sentry.md`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | **예외(정당화)** | 프레임워크 설정 바인딩만 켜는 변경. 사용자 결정(2026-09-09 KB-508 — Sentry 관련 테스트 전부 제거, 이후 관측 작업에 테스트 미작성). Complexity Tracking 참조 |
| II. Bounded Contexts | 통과 | 도메인 패키지 무접촉 |
| III. Layered Dependency Direction | 통과 | 소스 변경 없음 |
| IV. Persistence Ownership | 통과 | 영속 무접촉 |
| V. Domain Content Language Policy | 통과 | 무관 |

**Post-design re-check**: Phase 1 설계 후에도 동일 — 코드 파일 0개, 원칙 I 예외 외 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-510-ignore-no-resource-found/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — SDK 동작 확인(R1~R5)
├── data-model.md        # Phase 1 — 엔티티 없음(설정 값 모델만)
├── quickstart.md        # Phase 1 — 로컬 drop 로그·dev 검증 절차
├── contracts/
│   └── sentry-ignore-config.md   # Phase 1 — yml 계약·문서 변경 계약
└── tasks.md             # Phase 2 — /speckit-tasks 가 생성
```

### Source Code (repository root)

```text
api/src/main/resources/application.yml      # sentry: 블록에 ignored-exceptions-for-type 추가 (유일한 런타임 변경)
docs/observability/sentry.md                # "수집되지 않는 것" 항목 + 노이즈 조정 안내 갱신
```

**Structure Decision**: 기존 파일 두 개만 수정한다. 신규 Kotlin 소스·테스트·마이그레이션·batch 설정 변경 없음. `SentryRequestContextProcessor` 는 손대지 않는다 — SDK 가 프로세서 호출 전에 버리므로 `http.status` 태그 분기에 404 예외를 넣을 이유가 없다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 I 예외 — 자동 테스트 없음(spec FR-004·SC-003 의 "자동 검증" 을 dev 검증으로 대체) | 검증 대상이 우리 로직이 아니라 SDK 의 설정 바인딩·drop 판정이다. 테스트 컨텍스트는 DSN 부재로 Sentry 자동구성이 아예 뜨지 않아, 검증하려면 yml 문자열 읽기 테스트(KB-508 에서 사용자가 삭제한 `SentryConfigTest` 와 동일 형태)나 새 Spring 컨텍스트가 필요하다. 사용자가 2026-09-09 "Sentry 관련 테스트는 제거, 다음 관측 작업에도 만들지 말 것" 으로 결정했다 | yml 읽기 테스트: 삭제된 테스트의 재도입이라 기각. `SentryProperties` Binder 테스트: 같은 성격(설정 문자열 고정)이라 기각. 클래스명 **오타**는 부팅 실패(Boot `ClassEditor` 바인딩 오류)로 드러나므로 조용한 회귀는 "줄 삭제" 한 경우뿐이고, 이는 PR 리뷰 + dev 검증(quickstart)으로 막는다 |
