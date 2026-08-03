# Implementation Plan: 음식 사진 WebP 변환본 서빙

**Branch**: `kb-285-food-image-webp` | **Date**: 2026-08-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-285-food-image-webp/spec.md`

## Summary

**이미지를 생성 시점부터 webp 로 받는다**(R6 — 초기안 "PNG 로 받아 Lambda 가 변환" 폐기). 배치 요청 body 에 `output_format=webp`·`output_compression=80` 을 실어 OpenAI 가 webp 바이트를 돌려주게 하고, 회수기는 그 바이트를 `images/webp/food/{sha12}_{uuid16}.webp` 로 올린다. put 키와 `food.image_ref` 가 같은 값이라 경로 매핑 분기가 없다.

근거는 실측이다 — 운영 이미지 13장을 quality 80 webp 로 변환하니 **92.9% 감소**(24.97MB→1.78MB)에 육안 열화가 없었다. PNG 무손실 마스터가 실제로 필요한 용도(인쇄물)가 없고 재생성 비용도 낮아, 그 보험을 위해 변환 Lambda·레이어·IAM 롤·트리거·실패 알림을 상시 운영할 이유가 없다.

기존 적재분(620장)은 이미 `images/webp/food/` 로 변환해 뒀고, `image_ref` 는 Flyway 없이 운영 DB에 단발 UPDATE 로 갱신한다([quickstart.md](./quickstart.md) §3).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(:api) — 신규 의존 없음

**Storage**: MySQL(`food.image_ref` varchar(500) — 값만 바뀌고 스키마 변경 없음) + S3(원본 `images/food/`, 변환본 `images/webp/food/`)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers — `api/src/test/.../food/FoodImageBatchCollectServiceTest.kt`

**Target Platform**: `:api` bootJar 의 스케줄 회수기(`@Scheduled` + ShedLock)

**Project Type**: 백엔드 모듈러 모놀리스(:common / :api / :batch / :infra:*)

**Performance Goals**: 사진 1장 전송 용량 80% 이상 감소(약 2MB → 200KB 수준), 목록 6장 표시 시간 절반 이하

**Constraints**: 해상도 유지(리사이즈 없음), 원본 PNG 보존, 재시도 시 예약 파일명 불변, Flyway 마이그레이션 추가 금지

**Scale/Scope**: 변경 파일 2개(서비스 1 + 테스트 1) + 런북 문서. 기존 `image_ref` 보유 음식 전량 1회 갱신.

## Constitution Check

*GATE: Phase 0 이전 통과 필요. Phase 1 설계 후 재검증.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | `FoodImageBatchCollectServiceTest` 의 기대값(`imageRef` = webp, `fileName`/put 키 = png)을 먼저 고쳐 Red 확인 후 구현. |
| II. Bounded Contexts | PASS | 변경은 `com.kbap.api.food` 안에서 끝난다. 새 도메인 의존 없음. `Food.attachImage` 시그니처·의미 불변. |
| III. Layered Dependency | PASS | 새 모듈·seam·포트 없음. 스토리지 접근은 기존 `StorageObjectStore` 그대로. |
| IV. Persistence Ownership | PASS | 스키마·엔티티 변경 없음. 트랜잭션 경계(현행 `TransactionTemplate`) 그대로. |
| V. Language Policy | N/A | 언어·번역과 무관. |

**Post-Design 재검증**: 설계 결과가 서비스 1개의 private/companion 순수 함수 추가 + 호출 인자 1곳 변경이라 위 판정 그대로 PASS. Complexity Tracking 항목 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-285-food-image-webp/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 3건
├── data-model.md        # Phase 1 — image_ref 값 계약(스키마 변경 없음)
├── quickstart.md        # Phase 1 — Lambda 설정 + 백필 SQL + dev 검증 런북
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출물(여기서 만들지 않음)
```

contracts/ 는 만들지 않는다 — 노출 API 의 필드·타입·경로가 그대로고 `imageUrl` 문자열 값만 바뀐다(계약 변경 아님).

### Source Code (repository root)

```text
infra/llm/
├── src/main/kotlin/com/kbap/infra/llm/config/LlmModelProperties.kt   # ImageProps 에 outputFormat·outputCompression
├── src/main/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClient.kt  # 요청 body 에 두 필드 실음
└── src/test/kotlin/com/kbap/infra/llm/food/OpenAiFoodImageBatchClientTest.kt

api/
├── src/main/kotlin/com/kbap/api/food/FoodImageBatchCollectService.kt  # storageKeyOf → webp 키, put content-type
├── src/main/resources/application.yml                                 # kbap.llm.image.output-format/compression
└── src/test/kotlin/com/kbap/api/food/FoodImageBatchCollectServiceTest.kt
```

**Structure Decision**: 출력 포맷은 `size`·`quality` 와 같은 성격의 요청 파라미터라 `ImageProps` 에 설정으로 두고 `requestLineOf` 가 null 아닐 때만 실어보낸다(미설정 시 OpenAI 기본값 = png). 저장 경로는 백필된 기존 자산과 같은 `images/webp/food/` 를 재사용해 카탈로그를 한 곳에 모은다 — 새 prefix 를 만들면 기존 620장을 다시 옮기고 백필을 다시 해야 한다.

## Complexity Tracking

해당 없음 — 헌법 위반 없음.
