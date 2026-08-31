# Implementation Plan: 음식 이미지·스캔 이미지 저장 키 규약 정비

**Branch**: `kb-243-food-image-key` | **Date**: 2026-07-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-243-food-image-key/spec.md`

## Summary

저장 키 생성 지점 두 곳만 바꾸는 소규모 변경이다. (1) 배치 회수의 음식 이미지 키를 `images/food/{foodId}.png` → `images/food/{sha256(음식명)[:12]}_{uuid16}.png` 로 바꿔 재생성 시 캐시 무효화 문제를 없애고, (2) 스캔 업로드 발급 키를 `{prefix}/images/scan/{yyyy}/{MM}/{memberId}/{uuid}.{ext}` → `{prefix}/images/scans/{yyyy}/{mm}/{memberId}_{uuid}.{ext}` 로 개편한다. DB 스키마·API 계약·엔티티 변경 없음(키 컬럼은 varchar(500)으로 충분). 기존 저장 경로는 문자열 그대로 유효(소급 이관 없음).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Spring Boot 4.1)

**Primary Dependencies**: 기존 것만 사용 — `java.security.MessageDigest`(sha256), `java.util.UUID`. 신규 의존성 없음.

**Storage**: S3 (`StorageObjectStore`·`PresignedUploadPort` seam 경유) — 버킷 키 문자열만 변경, 인프라 어댑터 무변경.

**Testing**: Kotest BehaviorSpec (+ 기존 `FoodImageBatchCollectServiceTest`·`PresignedUploadServiceTest` 수정)

**Target Platform**: `:api` 모듈 단독 (배치 회수 스케줄러·업로드 발급 모두 api 앱 소속)

**Project Type**: 기존 모듈러 모놀리스 내 버그 수정/정비 — 신규 모듈·패키지 없음

**Performance Goals**: 해당 없음 (키 문자열 생성 로직)

**Constraints**: 외부 시스템 호출(S3 put)은 트랜잭션 밖 유지(헌법 Additional Constraints — 현행 구조 보존). 음식명 조회가 put 이전에 필요해짐 → put 전에 1회 읽기 추가, 이후 트랜잭션 분기(음식 삭제 시 fail)는 현행 유지.

**Scale/Scope**: 프로덕션 코드 3파일(회수 서비스·발급 서비스·UploadPurpose), 테스트 2~3파일.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (계획 준수 필요) | 키 형식 단위 테스트(FR-005)와 기존 테스트의 기대 키 수정을 먼저 Red 로 만들고 구현한다. |
| II. Bounded Contexts | PASS | 변경은 `com.kbap.api.food`·`com.kbap.api.image` 기능 패키지 내부. 도메인 간 의존 방향 변화 없음. |
| III. Layered Dependency Direction | PASS | seam(`common.port.storage`) 계약·구현 무변경. api → common 방향 유지. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·Flyway 무변경(`image_ref`·`file_name` varchar(500) 충분). 트랜잭션 경계 현행 유지. |
| V. Domain Content Language Policy | N/A | 언어 정책과 무관. |

**Post-Phase 1 재점검**: 위반 없음 — Complexity Tracking 불요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-243-food-image-key/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — not created here)
```

(contracts/ 없음 — 외부 노출 인터페이스(API 응답·경로) 변경이 없다. 발급 응답의 key 값 내용만 달라지며 스키마는 동일.)

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── food/FoodImageBatchCollectService.kt   # storageKeyOf(foodId) → storageKeyOf(foodName): 해시+uuid 키. put 전에 음식명 로드.
└── image/
    ├── PresignedUploadService.kt          # objectKey 포맷: .../{memberId}/{uuid}.{ext} → .../{memberId}_{uuid}.{ext}
    └── UploadPurpose.kt                   # MENU_SCAN prefix "scan" → "scans"

api/src/test/kotlin/com/kbap/api/
├── food/FoodImageBatchCollectServiceTest.kt   # 기대 키를 새 규약 정규식으로 교체 + 파일명 규칙 고정 테스트(FR-005) + 재생성 신규 키 검증(FR-003)
└── image/PresignedUploadServiceTest.kt        # 발급 키 정규식을 새 규약으로 교체
```

**Structure Decision**: 신규 파일 없이 기존 기능 패키지 내 수정만. 키 생성 로직은 각 서비스의 기존 위치(companion/private)에 유지 — 두 규약은 형식이 달라 공용 유틸로 묶지 않는다(YAGNI).

## Complexity Tracking

위반 없음 — 해당 없음.
