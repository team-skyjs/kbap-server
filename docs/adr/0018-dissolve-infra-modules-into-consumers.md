# 0018. infra 모듈 해체 — 어댑터 구현을 소비 모듈(common·api) 안으로 흡수

- **상태**: Accepted
- **날짜**: 2026-08-14
- **관련**: ADR-0010(supersede) · ADR-0016(infra 유지 결정 supersede) · PR #166

## Context

ADR-0016 모듈 다이어트는 도메인 모듈 13개를 3개 앱 모듈로 접으면서 외부 시스템 어댑터(`:infra:*`)만 SDK 의존 오염 방지를 이유로 모듈로 남겼다. 이후 어댑터는 6종(llm·auth·redis·storage·mq·place)까지 늘었는데, 실소비자를 조사한 결과:

- **api·batch 가 함께 쓰는 어댑터는 llm 하나뿐**이고, mq 는 batch 전용, 나머지 4종(auth·redis·storage·place)은 api 전용이다.
- batch 의 `:infra:storage` 의존은 콘텐츠 파이프라인 이관(KB-301) 잔해로, 조립 config 만 남고 소비자가 없는 죽은 의존이었다.
- 어댑터 6종 중 4종이 1~2파일 크기다. 모듈 하나의 존재 비용(빌드 파일·settings 등록·jacoco 배선)이 격리 이득을 넘어섰다.

같은 세션에서 common 의 도메인 서비스를 api 로 내리면서(소비자 기준 배치), 어댑터에도 같은 기준을 적용할 수 있음이 확인됐다.

## Decision

`:infra:*` 모듈 6종을 전부 해체하고, 구현 코드를 소비 모듈 안의 전용 패키지로 흡수한다. 모듈은 `:common`·`:api`·`:batch` 3개만 남는다.

- **batch 가 쓰는 어댑터 → `:common`**: `com.kbap.common.infra.llm`(Spring AI vision·embedding), `com.kbap.common.infra.mq`(SQS 아웃박스 발행).
- **api 전용 어댑터 → `:api`**: `com.kbap.api.infra.{auth,redis,storage,place}`.
- **seam 계약(`common.port.*`)은 그대로 `:common`** — 기능 코드는 여전히 port 인터페이스만 본다.
- **조립 창구 규칙은 ArchUnit 으로 이관**: 어댑터 구현 패키지(`common.infra..`·`api.infra..`) 직접 참조는 조립 config 패키지와 어댑터 자신에서만 허용(`ModuleBoundaryTest`). 커널·도메인·util·port 의 어댑터 비의존도 같은 테스트가 강제한다.
- SDK 의존은 소비 모듈 빌드 파일로 이동: common 에 spring-ai starter + aws-sqs(+AI BOM import), api 에 firebase-admin·jjwt·data-redis·aws-s3.
- batch 의 죽은 storage 의존과 `BatchStorageConfig` 는 함께 제거한다.

## Alternatives Considered

- **현행 유지(어댑터당 모듈)** — 소비자가 바뀌어도 조립만 바꾸면 되는 가장 변화-둔감한 형태지만, 6종 중 5종이 단일 소비자인 현실에서 모듈 관리 비용이 이득을 초과한다.
- **infra 안에서 통합(redis→auth, mq+storage→aws)** — 모듈 수는 줄지만 "SDK 는 infra" 격리 축과 컨테이너 디렉터리가 그대로 남는다. 모듈 경계로 얻던 컴파일 타임 격리가 이미 ArchUnit 으로 대체 가능함이 확인된 이상 절반의 해법이다.
- **어댑터 전부 common** — api 전용 SDK(firebase·jjwt)가 batch 클래스패스에 실린다. batch 가 쓰는 것만 common 에 두는 소비자 기준이 ADR-0016 배치 기준과 일관된다.

## Consequences

- 모듈이 7개→3개로 줄고, 새 어댑터 배치 결정이 도메인 서비스와 같은 기준("batch 도 쓰는가")으로 통일된다.
- **컴파일 타임 SDK 격리가 테스트 타임(ArchUnit)으로 격하된다** — api 기능 코드가 jjwt·firebase 를 import 해도 컴파일은 통과하고 `ModuleBoundaryTest` 가 잡는다. 종전 `runtimeOnly`(llm·redis) 수준의 차단은 사라진다.
- api 전용 SDK 가 api 컴파일 클래스패스에, spring-ai·aws-sqs 가 common 의존자 런타임에 노출된다(자동구성은 기존처럼 `spring.ai.model.*=none`·조건부 프로퍼티로 통제).
- batch 가 api 전용 어댑터를 새로 쓰게 되면 그 어댑터는 api→common 역이동이 필요하다 — 도메인 서비스 승격과 같은 규칙이므로 감수한다.
- 헌법·conventions 문서의 "infra 모듈" 문언 갱신이 뒤따른다. ADR-0010(:infra:llm 신설)과 ADR-0016 의 infra 유지 결정은 본 ADR 로 대체된다.
