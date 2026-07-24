# Implementation Plan: 메뉴판 사진 → 메뉴명·가격 추출 퀄리티 실험 (스파이크)

**Branch**: `kb-138-menu-price-mapping` | **Date**: 2026-07-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-138-menu-price-mapping/spec.md`

## Summary

메뉴판 사진의 **이미지 URL** 을 GPT vision(OpenAI chat API)에 직접 넘겨 메뉴명·가격 쌍을 구조화 JSON 으로 추출하고, 수기 정답 라벨 대비 지표 4종(메뉴명 정확도·가격 정확도·지연·토큰/비용)을 측정하는 **스파이크**다. 프로덕션 코드는 무변경 — 실험 하네스는 `:infra:llm` 의 **테스트 소스셋**에 opt-in 테스트(기존 `LlmSmokeTest` 의 `@EnabledIf` + 시스템 프로퍼티 패턴 재사용)로 두고, Spring AI `OpenAiChatModel` 에 `UserMessage` + URL `Media` 를 실어 호출한다(이미지 바이트는 하네스를 거치지 않음 — OpenAI 가 URL 을 직접 fetch). 산출물은 코드가 아니라 **결과 리포트**(지표·현행 방식 비교표·채택 결론·후속 이슈 목록)다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring AI 2.0 `spring-ai-starter-model-openai`(기존 `:infra:llm` 의존 재사용 — 신규 의존 0건), Jackson(테스트 클래스패스 기존재)

**Storage**: 없음(DB 무접촉). 실험 자산은 파일 — `specs/kb-138-menu-price-mapping/experiment/` 하위 manifest(JSON)·결과 리포트

**Testing**: Kotest BehaviorSpec (JUnit 5 플랫폼) — 순수 로직(파서·지표 계산)은 일반 단위 테스트, 실호출 하네스는 `@EnabledIf` opt-in 테스트

**Target Platform**: 로컬 개발 머신에서 수동 실행(CI 미실행 — opt-in 게이트)

**Project Type**: 실험 하네스(스파이크) — 기존 Gradle 멀티모듈 내 테스트 소스셋

**Performance Goals**: 해당 없음(측정 대상이 곧 성능 — 응답 지연·토큰·비용을 지표로 산출)

**Constraints**: 이미지 파일을 하네스/서버로 업로드하지 않음(URL 입력 고정, FR-001). 프로덕션 코드 diff 0(FR-008). 실 API 키 필요(`OPENAI_API_KEY`)

**Scale/Scope**: 샘플 10~20장, 단일 모델(GPT vision — 모델명은 프로퍼티로 교체 가능), 1회성 실험

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ PASS | 순수 로직(LLM 응답 파서 `MenuPriceParser`, 지표 계산 `ExperimentMetrics`)은 실패 테스트 먼저 작성(Red→Green). 실호출 하네스 스펙 자체는 수동 opt-in 실행 검증 도구로, `LlmSmokeTest` 와 동일한 기존 관례를 따른다. |
| II. Bounded Contexts | ✅ PASS | 도메인 모듈 무접촉. `:infra:llm` 테스트 소스셋만 추가. |
| III. Layered Dependency Direction | ✅ PASS | 모듈 의존 변경 0건. 기존 `:infra:llm` 내부에서만 작업. |
| IV. Persistence Encapsulation | ✅ PASS | 영속 코드 없음(DB 무접촉, 엔티티·리포지토리 신규 0건). |
| V. Domain Content Language Policy | ✅ PASS (N/A) | 사용자 노출 콘텐츠 없음 — 실험 산출물은 개발 문서. 추출 메뉴명은 한국어 원문 기준으로 라벨과 비교(현행 파이프라인 정책과 일치). |
| 추가 제약(외부 호출·트랜잭션) | ✅ PASS | DB 트랜잭션 자체가 없음. LLM 호출은 테스트 프로세스에서 직접 수행. |

**Post-Phase-1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-138-menu-price-mapping/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 이미지 URL 입력 방식·모델·구조화 출력 결정
├── data-model.md        # Phase 1 — 실험 데이터 구조(샘플·라벨·결과·지표)
├── quickstart.md        # Phase 1 — 실험 실행 방법
├── contracts/
│   └── experiment-files.md  # manifest·LLM 출력·결과 파일의 JSON 계약
├── experiment/          # 실험 자산(구현 단계에서 생성)
│   ├── samples.json     # 샘플 manifest — 이미지 URL + 조건 태그 + 정답 라벨(인라인)
│   ├── results.json     # 하네스 실행 산출(샘플별 추출 결과 + 지표)
│   └── report.md        # 결론 문서 — 지표 집계·현행 비교표·채택 판단·후속 이슈
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
infra/llm/src/test/kotlin/com/kbap/infra/llm/experiment/
├── MenuPriceParser.kt               # LLM 응답 JSON → MenuPriceItem 목록 (순수 로직, 펜스 제거 포함)
├── ExperimentMetrics.kt             # 라벨 대비 매칭·정확도·집계 계산 (순수 로직)
├── ExperimentModels.kt              # 샘플·라벨·결과 data class + manifest 로딩
├── MenuPriceParserTest.kt           # Red→Green 단위 테스트 (BehaviorSpec)
├── ExperimentMetricsTest.kt         # Red→Green 단위 테스트 (BehaviorSpec)
└── MenuBoardVisionExperimentTest.kt # 실호출 하네스 — @EnabledIf opt-in, 결과 파일 기록
```

**Structure Decision**: 프로덕션 소스(`src/main`)는 어디도 건드리지 않는다. 하네스는 `:infra:llm` 테스트 소스셋에 두는데, (1) Spring AI OpenAI 의존과 `LlmConfiguration.openAiChatOptions`(internal — 같은 모듈 테스트에서 접근 가능)를 재사용할 수 있고, (2) `LlmSmokeTest` 의 `@EnabledIf`(시스템 프로퍼티 게이트) 관례가 이미 있어 CI 안전이 보장되기 때문이다. 실험 데이터(manifest·결과·리포트)는 코드가 아니라 스펙 디렉터리 `experiment/` 에 커밋해 diff 리뷰와 재현이 가능하게 한다.

## Phase 0: Research 결정 요약

상세는 [research.md](research.md). 핵심 결정:

1. **이미지 입력 = URL 전달** — OpenAI chat API 는 `image_url` 입력을 지원하며 OpenAI 서버가 URL 을 직접 fetch 한다. Spring AI 에서는 `UserMessage` 에 URL 기반 `Media` 를 실으면 `image_url` 로 직렬화된다. 이미지 바이트가 하네스를 통과하지 않으므로 FR-001(파일 업로드 금지)과 실환경 전제(presigned URL)를 그대로 검증한다.
2. **호출 스택 = 기존 Spring AI `OpenAiChatModel` 직접 생성** — `LlmFanoutClient`/`LlmChatRequest` 는 텍스트 전용이라 쓰지 않는다(확장은 후속 이슈 — 프로덕션 무변경 원칙). 하네스가 `OpenAiChatModel.builder()` 로 vision 모델 인스턴스를 직접 만든다.
3. **구조화 출력 = 프롬프트 지시 + 관대한 파싱** — JSON 배열(`[{"name": "...", "price": 8000|null}]`)을 프롬프트로 강제하고, 코드펜스 제거 후 Jackson 파싱(기존 `ScannedNameParser` 관례). OpenAI `response_format`(json_schema)은 결과가 불안정할 때만 2차 시도.
4. **모델 = 시스템 프로퍼티로 주입, 기본 `gpt-4o-mini`** — vision 지원 모델. 비용/품질 비교를 위해 `-Dllm.vision.experiment.model=gpt-4o` 재실행 가능.
5. **샘플 호스팅 = 실험자가 외부 접근 가능한 URL 준비**(S3 presigned·GitHub raw 등) — 저장 방식은 실험 범위 밖, manifest 는 URL 만 담는다.

## Phase 1: Design 산출물

- [data-model.md](data-model.md) — `ExperimentSample`(URL·조건 태그·정답 라벨), `MenuPriceItem`(name·price), `SampleResult`(추출 목록·매칭 판정·지연·토큰·비용), `ExperimentSummary`(집계 지표)
- [contracts/experiment-files.md](contracts/experiment-files.md) — `samples.json`(입력 manifest), LLM 응답 JSON, `results.json`(산출) 계약
- [quickstart.md](quickstart.md) — 샘플 준비 → 라벨 작성 → 하네스 실행(`OPENAI_API_KEY` + `-Dllm.vision.experiment.enabled=true`) → 리포트 작성 절차

## 구현 흐름 (Phase 2 tasks 의 뼈대)

1. **샘플셋·라벨 준비**(수작업): 조건 섞인 메뉴판 사진 10장+ 를 접근 가능한 URL 로 준비, `samples.json` 에 URL·조건 태그·정답 라벨 작성 (FR-003·004)
2. **Red→Green: `MenuPriceParser`** — 정상 JSON·코드펜스·가격 null·다중 가격·비정상 응답 파싱 테스트 먼저 (FR-002)
3. **Red→Green: `ExperimentMetrics`** — 이름 정규화 매칭, 누락/오검출/오타 분류, 가격 정확도, 집계 (FR-005)
4. **하네스 `MenuBoardVisionExperimentTest`** — manifest 로드 → 샘플별 vision 호출(URL Media) → 지연/토큰/비용 수집 → `results.json` 기록, 실패 URL 은 원인 포함 보고 (FR-001·009)
5. **실험 실행 + `report.md` 작성**(수작업): 지표 검토, 현행 방식 비교표, 채택/미채택 결론, 후속 이슈 목록 (FR-006·007, SC-004)

## Complexity Tracking

> 헌법 위반 없음 — 해당 없음.
