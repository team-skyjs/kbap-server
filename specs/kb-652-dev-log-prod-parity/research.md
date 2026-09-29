# Research: dev 로그 출력을 prod 와 동일하게 — 설계 결정

Technical Context 에 NEEDS CLARIFICATION 은 없다. 조사 결과와 결정 5건이다.

## 조사 결과

| 사실 | 출처 | 영향 |
|------|------|------|
| api prod·staging 은 `logging.structured.format.console: ecs` + `spring.jpa.show-sql: false`. dev 는 `logging` 키 없음 + `show-sql: true`. local 도 `show-sql: true`(structured 없음). | `api/src/main/resources/application-{dev,staging,prod,local}.yml` | dev 에 두 키만 맞추면 api 는 prod 와 동일하다 |
| 배치는 어떤 프로필에도 `logging.structured` 가 없다. dev·local 만 `show-sql: true`, staging·prod 는 false. | `batch/src/main/resources/application-*.yml` | 배치 dev 는 `show-sql: false` 하나만 바꾼다 |
| 베이스 `application.yml` 의 `logging.pattern.correlation`(reqId·memberId 접두)은 텍스트 패턴용이다. ecs 인코더는 MDC 를 최상위 필드로 자동 포함하므로 dev 가 JSON 이 되어도 이 키는 그대로 두면 된다(prod 도 그렇게 쓰고 있다). | `api/src/main/resources/application.yml:36-40`, KB-130 | 베이스 파일 변경 없음 |
| 프로필별 `logging.level` 차이는 없다(api 는 어느 프로필에도 없음, 배치는 베이스에 한 줄뿐이라 전 프로필 동일). | grep `level:` 전 yml | 로그 수준은 이미 동일 — 손대지 않는다(spec Assumptions) |
| dev·prod 모두 같은 Terraform 모듈(`ecs-environment`)이 `awslogs` 드라이버로 CloudWatch 로그 그룹에 보낸다. | `iac/terraform/modules/ecs-environment/{api,batch,logs}.tf` | 수집 경로가 같으므로 형식만 맞추면 Logs Insights 에서 JSON 필드 검색이 된다 |
| `StructuredConsoleLoggingTest` 가 `listOf("staging", "prod")` 프로필 yml 을 읽어 `logging.structured.format.console == "ecs"` 를 검사하고, ecs 인코더가 MDC 를 JSON 필드로 내는지도 검사한다. | `api/src/test/kotlin/com/kbap/api/core/logging/StructuredConsoleLoggingTest.kt` | dev 를 목록에 추가하면 FR-006 충족 |
| develop 푸시 → `deploy-dev.yml`(api)·`deploy-batch-dev.yml`(batch, `batch/**`·`common/**` 변경 시) 이 dev 에 자동 배포한다. | `.github/workflows/` | PR 머지가 곧 dev 배포·실측 확인 기회 |

## D1. api dev 에 두 키만 추가한다

- **Decision**: `api/src/main/resources/application-dev.yml` 상단에 prod 와 동일한 `logging.structured.format.console: ecs` 블록(주석 포함, prod 파일과 같은 문구)을 넣고 `spring.jpa.show-sql` 을 `false` 로 바꾼다.
- **Rationale**: prod 와의 차이가 정확히 이 두 키다. 베이스 파일이나 logback 설정을 만지면 다른 프로필까지 영향이 간다(FR-004·FR-005 위반 위험).
- **Alternatives considered**: (a) 베이스 `application.yml` 에 structured 를 올리고 local 에서 끄기 — local yml 에 "끄는" 설정을 새로 넣어야 하고 staging·prod 파일도 손대게 된다. 기각. (b) dev 를 prod yml 로 import 하기(`spring.config.import`) — 데이터소스·키 접두 등 dev 고유값과 얽힌다. 기각.

## D2. 배치 dev 는 `show-sql: false` 만

- **Decision**: `batch/src/main/resources/application-dev.yml` 의 `show-sql` 을 `false` 로 바꾼다. 구조화 로그는 넣지 않는다.
- **Rationale**: 기준은 "각 앱의 prod 와 동일" 이다(spec Assumptions). 배치 prod 가 텍스트이므로 배치 dev 도 텍스트가 맞다. 배치를 JSON 으로 바꾸는 것은 prod 까지 같이 바꿔야 하는 별도 결정이다.
- **Alternatives considered**: 배치도 JSON 으로 — 범위 확장. 기각(필요하면 별도 태스크).

## D3. 기존 테스트의 프로필 목록에 dev 추가 — 새 테스트 없음

- **Decision**: `StructuredConsoleLoggingTest` 의 `listOf("staging", "prod")` 를 `listOf("dev", "staging", "prod")` 로 바꾸고 given 설명을 "dev·staging·prod" 로 맞춘다. 배치 `show-sql` 에 대한 테스트는 만들지 않는다.
- **Rationale**: 설정만 켜는 노출 변경엔 테스트를 두지 않는다는 프로젝트 결정(KB-380·KB-411)과 일치한다. 다만 이 테스트는 이미 존재하고 "프로필이 구조화 로그를 켰는가" 를 한 줄로 검사하므로 dev 를 포함하는 것이 FR-006 의 정확한 구현이다. `show-sql` 은 Hibernate 의 표준 출력 토글이라 프로퍼티 검사 외에 검증할 도메인 로직이 없다.
- **Alternatives considered**: dev 프로필로 컨텍스트를 띄워 실제 로그 라인을 검사 — 두 번째 SpringApplication 은 로깅 프로퍼티를 반영하지 않아(LogbackLoggingSystem JVM 당 1회) 불가능하다는 것이 이미 확인된 사실(CLAUDE.md 테스트 절). 기각.

## D4. 실측 확인은 두 단계 — 로컬 dev 프로필 기동, 그리고 dev 배포 후 CloudWatch

- **Decision**: (1) 머지 전: 로컬 MySQL·Redis 컨테이너에 `SPRING_PROFILES_ACTIVE=dev` 로 api 를 띄워 첫 로그 줄이 `{` 로 시작하고 `requestId`·`memberId` 필드가 있으며 SQL 이 안 찍히는지 본다. (2) 머지 후: develop 푸시로 자동 배포된 dev 의 CloudWatch 로그 그룹에서 Logs Insights 로 `requestId` 필드 검색이 되는지 본다(SC-004).
- **Rationale**: 프레임워크 토글은 실노출 확인으로 검증한다는 프로젝트 관행. dev 프로필은 `DB_URL` 등 환경변수를 요구하지만 값은 로컬 컨테이너를 가리켜도 된다(프로필이 결정하는 것은 로그·키 접두 같은 설정이지 실제 DB 주소가 아니다).
- **Alternatives considered**: 로컬 확인 생략하고 배포만 — 잘못 넣으면 dev 배포가 한 번 낭비된다. 로컬 기동은 2~3분이라 한다.

## D5. 범위 밖으로 남기는 것

- 배치 베이스 yml 의 `logging.level.com.kbap.infra.llm.provider: DEBUG` 는 존재하지 않는 패키지(현재 `com.kbap.common.infra.llm`)를 가리키는 죽은 설정이다. 전 프로필 공통이라 dev·prod 동일성에는 영향이 없다. 이 작업에서 건드리지 않고 별도 정리 후보로 보고한다.
- 부하 테스트 시나리오·램프업 계획은 이 작업이 아니다(spec Assumptions).
