# Feature Specification: prod Redis TLS 필수 대응 — 전 환경 동일 TLS 설정

**Feature Branch**: `kb-169-redis-tls`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "kb-169 간단 작업이라 바로 브랜치 파서 구현 들어가면 될듯? 모든 환경에 대해 동일하게 설정을 적용해줘" (Jira KB-169: prod ElastiCache Redis 가 전송 중 암호화 필수인데 API 앱에 Redis SSL 설정이 없어 평문 접속 시도 → 연결 실패 → refresh token 저장 실패로 로그인 API 500)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - prod 로그인 정상화 (Priority: P1)

prod 사용자가 소셜 로그인을 하면 서버가 refresh token 을 세션 저장소(Redis)에 저장하고 정상 응답을 돌려준다. 현재는 저장소 접속이 암호화 요구사항 불일치로 실패해 로그인 API 가 500 을 반환한다.

**Why this priority**: prod 로그인 자체가 불가능한 장애 상황 — 이 티켓의 존재 이유.

**Independent Test**: prod 배포 후 `/api/v1/auth/login` 호출이 성공하고 refresh token 이 발급·저장되는지 확인.

**Acceptance Scenarios**:

1. **Given** prod 환경(전송 중 암호화 필수 Redis), **When** 사용자가 로그인하면, **Then** refresh token 저장이 성공하고 로그인 응답이 정상(200) 반환된다.
2. **Given** prod 환경, **When** 서버가 Redis 에 접속하면, **Then** 접속은 TLS(전송 중 암호화)로 성립한다.

---

### User Story 2 - 전 환경 동일 설정 (Priority: P2)

개발자는 어느 환경 프로필을 열어도 Redis 접속 보안 설정이 동일한 방식으로 선언되어 있어, 환경 간 설정 드리프트로 인한 "prod 에서만 터지는" 장애가 재발하지 않는다.

**Why this priority**: 이번 장애의 근본 원인이 prod 에만 존재하는 인프라 요구사항(TLS 필수)이 앱 설정에 반영되지 않은 환경 간 비대칭이다. 사용자 지시: 모든 환경에 동일하게 적용.

**Independent Test**: 4개 환경 프로필(local·dev·staging·prod) 설정 파일을 비교해 Redis TLS 설정이 동일하게 존재하는지 확인.

**Acceptance Scenarios**:

1. **Given** 4개 환경 프로필 설정, **When** Redis 접속 설정을 비교하면, **Then** TLS 설정이 모든 환경에 동일하게 선언되어 있다.

---

### Edge Cases

- TLS 를 지원하지 않는 평문 Redis(예: 로컬 docker)에 TLS 설정으로 접속하면 연결이 실패한다 — 모든 환경 동일 적용 전제상 각 환경의 Redis 가 TLS 접속을 수용해야 하며, 수용 불가 환경이 발견되면 적용 방식을 재결정한다(plan 단계 확인 사항).
- Redis 접속 실패 시 로그인 API 는 현재처럼 500 으로 드러난다 — 본 작업은 접속 성립을 복구하는 것이며 실패 시의 에러 표현 개선은 범위 밖.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: prod 환경에서 서버의 Redis 접속은 전송 중 암호화(TLS)로 성립해야 한다.
- **FR-002**: Redis TLS 설정은 4개 환경 프로필(local·dev·staging·prod) 전부에 동일하게 적용되어야 한다.
- **FR-003**: 설정 적용 후 로그인 흐름(refresh token 저장 포함)이 기존 API 계약(요청·응답·에러코드) 변경 없이 동작해야 한다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: prod 배포 후 로그인 API 호출이 500 없이 성공한다(refresh token 저장 성공).
- **SC-002**: 4개 환경 프로필의 Redis TLS 설정이 100% 동일하다(설정 파일 비교로 검증 가능).
- **SC-003**: 애플리케이션 코드·API 계약 변경 0건 — 설정 변경만으로 장애가 해소된다.

## Assumptions

- Jira KB-169 DoD 의 "staging TLS 필수 여부 확인 후 판단" 항목은 사용자 지시("모든 환경에 대해 동일하게 설정을 적용")로 대체 — 확인 절차 없이 전 환경 동일 적용으로 확정한다.
- 모든 환경의 Redis 가 TLS 접속을 수용한다는 전제다. 로컬/개발 Redis 가 평문 전용인 경우가 확인되면 동일 선언을 유지하되 환경별 값 주입으로 해소한다(구체 방식은 plan 에서 결정).
- 인프라(ElastiCache) 측 변경은 범위 밖 — 앱 설정 변경만 다룬다.
- 배치 앱은 Redis 를 사용하지 않으므로 범위 밖.
