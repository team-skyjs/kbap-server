# Implementation Plan: 스캔 v2 경로 분리 · 비전 모델 교체 · LLM 단일 벤더 정리

**Branch**: `kb-320-scan-vision-ocr-ignore` | **Date**: 2026-08-11 (범위 재정의 반영) | **Spec**: [spec.md](./spec.md)

## Summary

세 덩어리다.

1. **스캔 v1/v2 경로 분리** — KB-319(PR #141)가 `X-API-Version` 헤더로 갈라 둔 서버 OCR 계약을 **URL 경로**(`/api/v2/scans`)로 옮긴다. v1 은 KB-319 이전 계약으로 동결한다. 요청에서 `items` 가 사라지는 리소스 구조 변경이므로 팀 정책상 경로가 담당한다.
2. **비전 모델 교체** — `gpt-4o-mini` → `gpt-5.6-luna`, 단가 0.2/1.2 명시, `temperature: 1.0`.
3. **LLM 단일 벤더 정리** — 다중 벤더 fan-out(Upstage·Gemini)과, kbap-langchain 이관(KB-301)으로 소비처를 잃은 배치 콘텐츠 LLM 경로를 제거한다.

부수적으로 `idx` 중복 서버 가드, 환경변수 통합, `.gitignore` 보강이 붙는다.

**프롬프트는 이 브랜치에서 바꾸지 않는다.** 초안의 목표(OCR 을 판단에서 배제)는 v2 가 `items` 를 받지 않는 **구조**로 달성되며, 프롬프트는 KB-319 가 develop 에 넣은 두 벌(`SYSTEM_PROMPT` / `SERVER_OCR_SYSTEM_PROMPT`)을 그대로 쓴다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(`spring-ai-starter-model-openai`, `spring-ai-starter-model-bedrock`)

**Storage**: 변경 없음. Flyway 마이그레이션 없음.

**Testing**: Kotest `BehaviorSpec`. 단위 = `:infra:llm` 가짜 `ChatModel`, 통합 = `:api` MockMvc + `FakeMenuBoardVisionExtractor`·`FakeSimilarFoodSearch` + MySQL Testcontainers.

**Target Platform**: Linux 서버(`:api` bootJar), 운영 인스턴스 2대

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — `:api`·`:infra:llm`·`:common`·`:batch`

**Performance Goals**: 스캔 응답 p50 8초 이내. vision 타임아웃 60초·재시도 0 유지.

**Constraints**: v1 계약 불변(테스트 수정 0건으로 증명). 외부 LLM 호출은 트랜잭션 밖 유지. 스캔 1회 비용은 현행의 5배 이내.

**Scale/Scope**: develop 대비 60파일, +420/−2200. 신규 프로덕션 파일 4개(v2 컨트롤러·API·요청·응답), 삭제 20여 개.

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First** | ⚠️ 부분 | 신규 로직(`idx` 중복 가드)은 실패 테스트 선행으로 진행했다. 그러나 **v1/v2 경로 분리는 KB-319 의 기존 테스트를 옮겨 온 것**이라 Red 선행이 아니다 — 동작이 새로 생긴 게 아니라 전달 방식(헤더→경로)만 바뀌었고, 옮긴 시나리오가 그대로 통과함을 회귀로 확인했다. 삭제 작업(LLM 정리)도 Red 대상이 아니다. Complexity Tracking 에 기록. |
| **II. Bounded Contexts** | ✅ PASS | 신규 도메인 의존 없음. v2 코드는 `com.kbap.api.scan` 안에 머문다. |
| **III. Layered Dependency Direction** | ✅ PASS | seam `MenuBoardVisionExtractor` 시그니처 불변. v2 는 기존 seam 을 빈 OCR 목록으로 호출할 뿐이다. `:common` 의 port 는 3종으로 **줄었다**(방향 위반 없음). |
| **IV. Persistence Ownership** | ✅ PASS | 엔티티·리포지토리·마이그레이션 변경 없음. 트랜잭션 경계 불변. |
| **V. Domain Content Language Policy** | ✅ PASS | `lang` 필수·폴백 정책 불변. v2 응답도 동일 규칙. |
| **Additional Constraints** | ✅ PASS | 외부 LLM 호출 트랜잭션 밖 유지. 도메인 모델 직접 노출 없음. |

**게이트 결과**: 원칙 I 에 부분 이탈이 있어 Complexity Tracking 에 기록한다. 나머지 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-320-scan-vision-ocr-ignore/
├── plan.md · spec.md · research.md · data-model.md · quickstart.md · tasks.md
├── contracts/
│   ├── scan-api.md       # v1 동결 계약 + v2 신규 계약
│   └── vision-prompt.md  # 프롬프트를 바꾸지 않는다는 기록 + 근거
└── checklists/requirements.md
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/scan/
├── ScanController.kt        # [수정] 헤더 분기 제거 — v1 동결
├── ScanApi.kt               # [수정] 헤더 파라미터·v2 설명·v2 예시 제거
├── ScanRequest.kt           # [수정] items 를 @NotEmpty 필수로 복원
├── ScanResponse.kt          # [수정] similarFood 제거 (v2 전용)
├── ScanV2Controller.kt      # [신규]
├── ScanV2Api.kt             # [신규] swagger — v1 과의 차이표 포함
├── ScanV2Request.kt         # [신규] imagePath 만
├── ScanV2Response.kt        # [신규] idx 없음 · similarFood 있음
└── ScanService.kt           # [수정] 진입점 2개 + 공유 scan() + idx 중복 가드

api/src/main/kotlin/com/kbap/api/core/config/
└── WebConfig.kt             # [수정] 보호 경로에 /api/v2/scans 등록

api/src/main/resources/application.yml   # [수정] vision 모델·단가·온도, 환경변수 통합
infra/llm/…                              # [수정·삭제] 단일 벤더 정리(20여 파일)
common/…/port/llm/                       # [삭제] 소비처 없는 seam 3종
```

**Structure Decision**: v2 는 DTO·컨트롤러·swagger 를 별도 파일로 두고 서비스만 공유한다. 응답 필드가 실제로 다르므로(`idx` 없음 / `similarFood` 있음) DTO 를 공유하면 두 경로 모두에서 "항상 null 인 필드"가 생긴다. 서비스는 진입점만 둘로 두고 내부 `scan()` 을 공유해 매칭·이력·카운트 로직 중복을 막는다.

## 구현 순서 (완료됨)

1. develop 병합 — KB-319 반영, 충돌 파일은 develop 쪽 채택
2. v1 복원 — 헤더 분기·`items` nullable 완화·`similarFood` 제거
3. v2 신설 — 컨트롤러·API·요청/응답 DTO, 서비스 진입점 추가
4. 보호 경로 등록(`WebConfig`)
5. 테스트 이관 — KB-319 의 헤더 시나리오 8건을 v2 경로로, 폴백 시나리오 2건은 제거
6. `idx` 중복 가드 + v1 회귀 테스트 2건 재추가
7. `./gradlew build` 전 모듈 그린

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 I — v1/v2 분리에 Red 선행 없음 | 동작이 신규가 아니라 **전달 방식 변경**(헤더→경로)이다. KB-319 의 시나리오를 경로만 바꿔 옮겼고, 옮긴 뒤 통과함을 확인했다 | 같은 시나리오를 일부러 깨뜨렸다가 고치는 절차는 형식만 만족시키고 회귀 안전성을 더해주지 않는다 |
| v1/v2 DTO 4개 신설(중복) | 응답 필드가 실제로 다르다 — v1 은 `idx`, v2 는 `similarFood` | 단일 DTO 공유는 두 경로 모두에 "항상 null 인 필드"를 남겨 계약을 흐린다 |
