# Implementation Plan: 임베딩 생성 포트 및 인프라 어댑터

**Branch**: `kb-299-bedrock-embedding-port` | **Date**: 2026-08-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-299-bedrock-embedding-port/spec.md`

## Summary

KB-299(음식 유사도 검색 파이프라인)의 임베딩 생성 구간만 선행 구현한다. `:common`에 경로 중립 seam `TextEmbeddingClient`(텍스트 목록 → 1024차원 벡터 목록, 순서 보존)를 두고, `:infra:llm`에 Spring AI 2.0 Bedrock 스타터의 `BedrockTitanEmbeddingModel`(Titan Text Embeddings V2)로 구현한다. 기존 LLM 3종과 동일하게 `kbap.llm.embedding.*` 프로퍼티 + `@ConditionalOnProperty` 명시 빈 구성으로 미설정 시 부팅 안전을 보장한다. 벡터 DB(Qdrant)·적재 배치·similar API·스캔 통합은 범위 밖(KB-299 잔여).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(BOM 관리) — 신규: `org.springframework.ai:spring-ai-starter-model-bedrock`(Maven Central 2.0.0 확인, AWS SDK v2 `bedrockruntime` 전이)

**Storage**: 없음 — 이 기능은 DB·스키마 변경 0건(벡터 저장처는 범위 밖)

**Testing**: Kotest BehaviorSpec(JUnit 5 플랫폼) — 페이크 기반 단위 테스트 + 자격증명 조건부 스모크(`LlmSmokeTest` 패턴)

**Target Platform**: JVM 서버(api·batch bootJar) — 실호출은 EC2 인스턴스 역할(`bedrock:InvokeModel` 부착 완료) 또는 로컬 AWS 자격증명

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 기존 `:common`·`:infra:llm` 확장(신규 모듈 없음)

**Performance Goals**: 비실시간 배치 묶음(수백~수천 건) + 동기 단건/소량 호출 수용. 명시 지연 목표 없음(스캔 흐름 자체가 수초 LLM 흐름 — 임베딩 단건 추가 지연은 상대적으로 미미). 타임아웃은 프로퍼티로 조정 가능하게 둔다.

**Constraints**: 미설정 환경(로컬·CI·전 프로필) 부팅 무영향 필수. `common.port`는 Spring/JPA-free(ArchUnit 강제). 부분 성공 반환 금지 — 실패는 예외 전파.

**Scale/Scope**: seam 인터페이스 1개 + 어댑터 1개 + 구성 확장 + 테스트. 프로덕션 코드 신규 파일 ~3개 수준.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 계약 동작(순서 보존·개수 일치·차원 검증·빈 목록 단락·실패 전파)을 페이크 `EmbeddingModel`로 Red 먼저 작성. 구성 조건부 생성은 `LlmConfigurationBootSafetyTest` 패턴의 ApplicationContextRunner 테스트로 선행 |
| II. Bounded Contexts | PASS | 도메인 코드 변경 없음. seam은 `common.port.llm`(기존 infra 계약 분류 기준 — 구현 모듈이 `:infra:llm`) 소속. 도메인→포트 역방향 의존 없음 |
| III. Layered Dependency Direction | PASS | `:infra:llm` → `:common` 기존 방향 그대로. 계약은 `:common`, 구현은 `:infra:llm`, 조립은 부트앱 스캔(+`@ConditionalOnProperty`) — 기존 LLM seam 4종과 동일 구조 |
| IV. Persistence Ownership | PASS(해당 없음) | 영속 코드·엔티티·리포지토리 변경 없음 |
| V. Domain Content Language Policy | PASS(해당 없음) | 콘텐츠·번역 정책과 무관. 임베딩 입력 텍스트 조립은 호출자 소관(범위 밖) |

**Post-Phase 1 재평가**: PASS — 설계 산출물(contracts/data-model)이 위 판정을 바꾸지 않음. 위반 없음 → Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-299-bedrock-embedding-port/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── text-embedding-client.md   # seam 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/port/llm/
└── TextEmbeddingClient.kt                    # [신규] seam — Spring-free 순수 계약

infra/llm/src/main/kotlin/com/kbap/infra/llm/
├── embedding/
│   └── SpringAiTextEmbeddingClient.kt        # [신규] Spring AI EmbeddingModel 위임 어댑터(차원 검증 포함)
└── config/
    ├── LlmConfiguration.kt                   # [수정] embedding 빈 추가(@ConditionalOnProperty kbap.llm.embedding.enabled)
    └── LlmModelProperties.kt                 # [수정] EmbeddingProps(model·region·dimension·timeout) 추가

infra/llm/build.gradle.kts                    # [수정] spring-ai-starter-bedrock 의존 추가
gradle/libs.versions.toml                     # [수정] spring-ai-starter-bedrock 카탈로그 엔트리

infra/llm/src/test/kotlin/com/kbap/infra/llm/
├── embedding/SpringAiTextEmbeddingClientTest.kt   # [신규] 페이크 EmbeddingModel 단위 테스트
├── config/LlmConfigurationBootSafetyTest.kt       # [수정] embedding 미설정 시 빈 미생성 검증 추가
└── config/EmbeddingSmokeTest.kt                   # [신규] 자격증명 조건부 실호출 스모크(LlmSmokeTest 패턴)
```

**Structure Decision**: 신규 모듈 없이 기존 `:infra:llm`을 확장한다(KB-299 작업 순서 3의 결정 그대로 — "『:infra:llm』에 Bedrock 임베딩 클라이언트"). seam은 구현 모듈 기준 분류 규칙(CLAUDE.md — `common.port.{llm,storage,auth}`)에 따라 `common.port.llm`에 둔다. `spring.ai.model.embedding: none`이 api·batch 양쪽 yml에 이미 있어 스타터 자동구성 유입 차단은 추가 작업 없음.

## Complexity Tracking

위반 없음 — 해당 없음.
