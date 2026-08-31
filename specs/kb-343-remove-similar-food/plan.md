# Implementation Plan: 스캔 2.0 응답 구조 통일 — similarFood 제거

**Branch**: `kb-343-remove-similar-food` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-343-remove-similar-food/spec.md`

## Summary

스캔 2.0 의 비매칭(DB miss) 메뉴 유사 음식 대체를 폐기한다 — `SimilarFoodResolver` 와 응답·조립의 similarFood 흔적을 전부 삭제하고, 비매칭 항목은 1.0 원칙(추출 결과 그대로·UNKNOWN)으로 내린다. `similarFoodFallback` 플래그가 겸하던 v2 빈 추출 400 게이트는 `requireDetectedMenu` 로 개명해 유지한다. 벡터 적재 파이프라인(KB-328)·검색 seam 은 존치(스캔 소비 코드만 제거). 스키마·이미지 필드 변경 없음, 2.0 매핑 내 즉시 적용.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택)

**Primary Dependencies**: 변경 없음 — 삭제 전용 작업(신규 의존 0)

**Storage**: 변경 없음 — DB·DocumentDB 스키마 무변경

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers (기존 ScanControllerTest 시나리오 전환)

**Target Platform**: `:api` 스캔 기능 패키지만 (`:common` seam·batch 무변경)

**Project Type**: web-service — 기존 응답 계약 축소

**Performance Goals**: 비매칭 항목 처리에서 임베딩·벡터 검색 외부 호출 0건(SC-002 — 지연·비용 제거)

**Constraints**: v2 빈 추출 400(KB-330)·스캔 1.0·매칭 판정·avoidances·currency 불변 · 무버전 적용

**Scale/Scope**: 파일 6~7개 수정 + 1개 삭제 + 테스트 전환 — 소형(순삭제 위주)

## Constitution Check

*GATE: 통과(위반 없음). Phase 1 설계 후 재평가 — 동일.*

- **I. Test-First**: "similarFood 부재" Red 시나리오 선작성 후 삭제(Green). 통과.
- **II. Bounded Contexts**: api 스캔 기능 패키지 내부 삭제 — 도메인 경계 무변. 통과.
- **III. Layered Dependency Direction**: seam(`common.port.llm`·`common.domain.food.vector`)은 존치, api 소비자만 줄어든다 — 방향 불변. 통과.
- **IV. Persistence Ownership**: 영속 무변경. 통과.
- **V. Domain Content Language Policy**: lang 규칙 무변경(비매칭 표시명은 종전대로 정제 한국어명). 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-343-remove-similar-food/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 5건(범위·플래그 분리·정리 지점·적용 방식·테스트 전략)
├── quickstart.md        # Phase 1 — 수동 검증
├── contracts/
│   └── scan-v2-no-similar-food.md  # 제거 후 응답 계약
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

(data-model.md 없음 — 데이터 변경이 없는 순수 응답 계약 축소)

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/scan/
├── SimilarFoodResolver.kt        # 삭제
├── ScanService.kt                # resolver 주입·resolveSimilarFoods·toSimilarFood 제거, similarFoodFallback → requireDetectedMenu 개명
├── ScanResult.kt                 # SimilarFood 클래스·similarFood 필드 제거
├── ScanV2Response.kt             # SimilarFoodResponse·similarFood 필드·from 조립 제거, avoidances description 문구 정리
└── ScanV2Api.kt                  # swagger 의 similarFood 언급 제거
api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt  # similarFood 시나리오 → 필드 부재·1.0 원칙 검증으로 전환
```

**Structure Decision**: 순삭제 — 신규 파일 없음. 존치 대상(`TextEmbeddingClient`·`FoodVectorSearcher` seam/구현/조립 config·KB-328 배치)은 한 줄도 건드리지 않는다.

## 구현 노트 (Phase 1 설계 확정)

- `requireDetectedMenu` 개명으로 v2 빈 추출 400(MENU_BOARD_NOT_DETECTED, KB-330 계약)이 similarFood 제거에 휩쓸리지 않게 격리한다.
- api 조립 config 에서 SimilarFoodResolver 전용 배선이 있으면 함께 제거하되, embedding·vector-search 빈 정의는 미사용이어도 존치(R1).
- OpenAPI 스냅샷이 스키마 축소로 깨지면 갱신 절차대로 재생성.
- `kbap.vector.similarity-threshold` 프로퍼티는 resolver 삭제로 미참조가 된다 — yml 잔존은 무해하나 만나는 김에 정리(주석 규약 밖 파일).

## Complexity Tracking

> 위반 없음 — 해당 없음.
