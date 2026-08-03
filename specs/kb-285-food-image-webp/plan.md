# Implementation Plan: 음식 사진 WebP 변환본 서빙

**Branch**: `kb-285-food-image-webp` | **Date**: 2026-08-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-285-food-image-webp/spec.md`

## Summary

이미지 배치 회수(`FoodImageBatchCollectService.handleResult`)에서 **S3 업로드 키(PNG)와 DB 기록 값(webp)을 분리**한다. 지금은 같은 문자열 `key` 하나가 `storageObjectStore.put` · `food.attachImage` · `item.done` 세 곳에 쓰이는데, `attachImage` 에만 webp 경로를 넘긴다. 매핑은 순수 함수 `webpRefOf(pngKey)` — `images/food/X.png` → `images/webp/food/X.webp` — 하나로 고정한다. 나머지(파일명 예약, 재시도 멱등, 비용 이벤트, 상태 전이)는 전부 그대로 둔다.

변환 자체(S3 PutObject → Lambda → `images/webp/food/`)와 IAM·DLQ·알람은 이 저장소 밖 인프라 작업이라 코드 변경이 없고, 절차만 [quickstart.md](./quickstart.md) 에 런북으로 남긴다. 기존 적재분의 `image_ref` 는 Flyway 없이 운영 DB에 단발 UPDATE 로 갱신한다(같은 런북).

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
api/
├── src/main/kotlin/com/kbap/api/food/
│   └── FoodImageBatchCollectService.kt      # 변경: put 키(png) ↔ attachImage 값(webp) 분리 + webpRefOf 추가
└── src/test/kotlin/com/kbap/api/food/
    └── FoodImageBatchCollectServiceTest.kt  # 변경: webp 기대값 + 매핑 규칙 테스트
```

**Structure Decision**: 기존 파일 2개만 손댄다. `webpRefOf` 는 `storageKeyOf` 와 짝이므로 같은 `FoodImageBatchCollectService.companion` 에 둔다 — 자동 기록 경로의 유일한 작성자가 이 서비스라 공용 유틸(`common.util`)로 올릴 이유가 없다(관리자 화면의 `imageRef` 직접 입력은 운영자가 값을 그대로 넣는 별개 경로).

## Complexity Tracking

해당 없음 — 헌법 위반 없음.
