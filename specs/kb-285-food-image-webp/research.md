# Research: 음식 사진 WebP 변환본 서빙

> **R6(2026-08-04)이 R1·R2·R4·R5 를 대체한다.** 아래 R1~R5 는 "PNG 로 받아 Lambda 가 변환"을 전제한 초기 결정이며, 실측 후 "**생성 시점부터 webp 로 받기**"로 방향을 바꿨다. 이력 보존용으로 남긴다.

## R6. PNG 원본을 보존할 가치가 있는가 (초기 전제 폐기)

- **Decision**: 이미지 생성 요청에 `output_format=webp`·`output_compression=80` 을 실어 **처음부터 webp 를 받는다**. PNG 무손실 마스터를 보존하지 않고, 변환 Lambda·트리거·IAM 롤·실패 알림을 전부 폐기한다. 저장 경로는 백필된 기존 자산과 같은 `images/webp/food/` 를 쓴다.
- **Rationale**: 운영 이미지 13장 실측에서 quality 80 webp 가 **92.9% 감소**(24.97MB→1.78MB, 평균 1,921KB→137KB)로 확인됐고 육안 열화가 없었다. 반면 PNG 마스터가 실제로 필요한 용도는 인쇄물 정도뿐이다 — 다중 해상도 파생·차세대 포맷 전환·배경 제거·비전 재검수는 전부 webp 원본에서 해도 결과가 같다. 결정적으로 **재생성 비용이 낮아**(전량 재생성 $10 안팎) "마스터 분실 = 복구 불가"가 아니다. 그 보험료로 Lambda·레이어·롤·트리거·DLQ 를 상시 운영하는 건 수지가 맞지 않는다.
- **부수 효과**: put 키와 `food.image_ref` 가 다시 같아져 `webpRefOf` 분기가 사라진다(R1·R2 의 문제 자체가 소멸). 배포 순서 결합(인프라 선행)과 변환 지연 구간(R5)도 함께 사라진다.
- **되돌릴 수 있는 결정**: 기존 620 장 PNG 는 그대로 남아 있고, 마스터가 필요해지면 그 시점부터 다시 PNG 로 받으면 된다. 잃는 것은 전환~복귀 사이 생성분의 마스터뿐이다.
- **전제(검증 필요)**: 사용 모델(`gpt-image-2`)이 Batch API 요청 body 의 `output_format`·`output_compression` 을 수용해야 한다. 미지원이면 배치 전량이 400 으로 실패하므로 **배포 전 단건 호출로 확인**한다. 미지원 시 R1~R5 의 Lambda 구조로 복귀한다.
- **Alternatives considered**: PNG 유지 + Lambda(초기안) — 실측 후 이득 대비 상시 운영 비용이 크다고 판단. 두 벌 저장(원본+변환본) — 스토리지 비용은 사소하나 파이프라인 조각 수가 문제였다.

---

## R1. 변환본 경로 매핑을 어디에 두는가

- **Decision**: `FoodImageBatchCollectService.companion` 에 순수 함수 `webpRefOf(pngKey: String): String` 을 추가한다(`storageKeyOf` 바로 옆). 규칙은 접두 `images/food/` → `images/webp/food/`, 확장자 `.png` → `.webp`.
- **Rationale**: `food.image_ref` 를 자동으로 기록하는 곳은 이 서비스의 `handleResult` 한 곳뿐이다(`grep attachImage` 확인). 키 생성 규칙(`storageKeyOf`)과 짝이라 같은 자리에 있어야 규칙 변경 시 한 파일만 본다.
- **Alternatives considered**:
  - `common.util.ImageUrls` 에 배치 — 그 유틸은 "CDN 베이스 + ref 조립" 책임이고, batch·infra 어느 쪽도 이 매핑을 쓰지 않아 공용화 이득이 없다.
  - 설정값(`kbap.food-image.webp-prefix`)으로 주입 — 환경별로 달라질 값이 아니다. 바꿀 일이 없는 값에 설정을 만들지 않는다.
  - `Food.attachImage` 안에서 변환 — 엔티티가 스토리지 레이아웃을 알게 되고, 관리자 수동 입력 경로까지 오염된다.

## R2. S3 업로드 키와 DB 기록 값의 분리 지점

- **Decision**: `handleResult` 에서 `key`(예약·put·`item.done` 용 PNG 키)는 그대로 두고, `food.attachImage(webpRefOf(key))` 만 바꾼다.
- **Rationale**: DoD 가 "예약 로직은 기존 PNG 키 기준 유지"를 명시한다. `image_batch_item.file_name` 은 재시도 시 고아 객체를 막는 **업로드 키 예약** 값이므로 실제 put 대상과 같아야 한다. 여기를 webp 로 바꾸면 재시도 때 예약 키와 put 키가 어긋난다.
- **Alternatives considered**: `file_name` 도 webp 로 저장 — 예약의 의미(=업로드 대상)와 어긋나고, 기존 예약분(PENDING 잔류 항목)과 혼재해 재시도 경로가 깨진다.

## R3. 기존 `image_ref` 백필 방법

- **Decision**: Flyway 마이그레이션을 만들지 않고 운영 DB에 단발 UPDATE 를 실행한다(런북: quickstart.md). 조건절을 `image_ref LIKE 'images/food/%.png'` 로 잡아 멱등하게 만든다.
- **Rationale**: 이슈가 "Flyway 마이그레이션 없이 직접 처리"로 못박았다. 실제로 이건 스키마가 아니라 1회성 데이터 정정이고, 로컬·테스트 DB 는 시드가 다시 만들어져 마이그레이션에 남길 이득이 없다. 갱신 후 행은 조건에 다시 걸리지 않아 재실행해도 안전하다.
- **Alternatives considered**:
  - Flyway 데이터 마이그레이션 — 이미 적용된 환경별 데이터 상태에 결합되고, 되돌릴 때 이력만 지저분해진다.
  - 관리자 API 로 일괄 갱신 엔드포인트 추가 — 1회 쓰고 버릴 코드에 엔드포인트·권한·테스트가 붙는다. 기각.

## R4. 변환 파이프라인(Lambda)의 저장소 내 위치

- **Decision**: 저장소에 코드·IaC 를 추가하지 않는다. 이 repo 에는 Terraform/CDK 등 인프라 코드가 없고(`infra/` 는 Gradle 인프라 어댑터 모듈), Lambda·IAM·DLQ 는 AWS 콘솔/CLI 로 구성한다. 설정 요건만 quickstart.md 에 남긴다.
- **Rationale**: 없는 IaC 체계를 이 이슈에서 새로 세우는 건 범위 밖이다. 백엔드 코드가 알아야 할 계약은 "변환본이 `images/webp/food/{같은 파일명}.webp` 에 생긴다" 한 줄뿐이다.
- **Alternatives considered**: 애플리케이션에서 직접 webp 변환 후 두 벌 업로드 — 회수 경로에 이미지 처리 라이브러리와 CPU 부하를 들이고, 실패 시 회수 트랜잭션까지 얽힌다. 이슈 결정(S3 이벤트 → Lambda)과도 다르다.

## R5. 변환 지연 구간(변환본 미생성) 처리

- **Decision**: 별도 처리를 하지 않는다. 회수 직후 짧은 구간에는 `image_ref` 가 가리키는 변환본이 아직 없을 수 있으나, 회수된 음식은 `PENDING_REVIEW` 로 들어가 수동 검수를 거쳐야 `READY` 가 되므로 사용자 노출 전에 변환이 끝난다.
- **Rationale**: 콘텐츠 상태 흐름(INCOMPLETE→TEXT_READY→PENDING_REVIEW→READY, 마지막이 수동)이 이미 자연스러운 유예 구간이다. 폴백·재시도 로직을 넣는 건 존재하지 않는 문제를 막는 코드다.
- **Alternatives considered**: 변환본 head 확인 후 기록 — 회수 경로에 S3 왕복과 대기가 붙고, 실패 시 재시도 설계가 필요해진다. 기각.
