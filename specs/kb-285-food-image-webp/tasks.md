---

description: "Task list for kb-285 음식 사진 WebP 서빙"
---

# Tasks: 음식 사진 WebP 서빙

**Input**: Design documents from `/specs/kb-285-food-image-webp/`

**Prerequisites**: plan.md, spec.md, research.md(R6), data-model.md, quickstart.md

**Tests**: Test-First는 **NON-NEGOTIABLE**(헌법 원칙 I). US1 은 실패 테스트를 먼저 쓰고 Red 를 확인한 뒤 구현한다. US2 는 운영 작업(코드 변경 없음)이라 실행 전후 검증 쿼리로 대신한다.

> **방향 전환(2026-08-04, research.md R6)**: 초기안은 "PNG 로 받아 S3 이벤트 Lambda 가 webp 변환"이었으나, 실측(92.9% 감소·열화 없음) 후 **생성 시점부터 webp 로 받는** 방식으로 바꿨다. Lambda·레이어·IAM 롤·트리거·실패 알림은 전부 폐기 대상이며, 아래 Phase 5 가 그 정리를 담는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 의존 없음)
- **[Story]**: US1 / US2

## Phase 1: Setup · Phase 2: Foundational

**해당 없음** — 신규 의존성·모듈·스키마 변경이 없다.

---

## Phase 3: User Story 1 - 신규 음식 사진이 webp 로 생성·서빙된다 (Priority: P1) 🎯 MVP

**Goal**: 이미지 생성 요청이 webp 를 요구하고, 회수기가 그 바이트를 `images/webp/food/{sha12}_{uuid16}.webp` 로 저장·기록한다.

**Independent Test**: `./gradlew build` — 요청 body 에 `output_format`·`output_compression` 이 실리고, 회수 후 저장 키와 `image_ref` 가 같은 webp 경로.

### Tests for User Story 1 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T001 [US1] `infra/llm/src/test/.../OpenAiFoodImageBatchClientTest.kt` — `ImageProps` 에 `outputFormat`·`outputCompression` 을 주고 요청 body 에 `output_format="webp"`·`output_compression=80` 이 실리는지, 미설정이면 두 필드가 없는지 검증. **Red 확인**.
- [X] T002 [US1] `api/src/test/.../FoodImageBatchCollectServiceTest.kt` — `FOOD_IMAGE_KEY_PATTERN` 을 `^images/webp/food/[0-9a-f]{12}_[0-9a-f]{16}\.webp$` 로 바꾸고, `imageRef` == 저장 키 단언으로 되돌린다(경로 매핑 분기 제거). 예약 키 리터럴도 webp 로. **Red 확인**.

### Implementation for User Story 1

- [X] T003 [US1] `LlmModelProperties.ImageProps` 에 `outputFormat: String?`·`outputCompression: Int?` 추가.
- [X] T004 [US1] `OpenAiFoodImageBatchClient.requestLineOf` 에서 두 값이 non-null 일 때만 body 에 실음(미설정 시 OpenAI 기본값 = png).
- [X] T005 [US1] `api/src/main/resources/application.yml` 의 `kbap.llm.image` 에 `output-format: webp`·`output-compression: 80`.
- [X] T006 [US1] `FoodImageBatchCollectService` — `storageKeyOf` 를 `images/webp/food/{hash}_{uuid}.webp` 로, `put` content-type 을 `image/webp` 로, `attachImage(key)` 로 원복하고 `webpRefOf` 삭제.
- [X] T007 [US1] `./gradlew build` Green 확인(전 모듈 + ArchUnit).

**Checkpoint**: 코드 완료. 배포 전 T008 검증 필수.

---

## Phase 4: 배포 전 검증 · 백필 (Priority: P1/P2)

- [ ] T008 [US1] **모델이 `output_format` 을 받는지 단건 호출로 검증**(quickstart §2). 400 `Unknown parameter` 면 이 브랜치를 배포하지 말고 research.md R1~R5(Lambda) 구조로 복귀한다. **가장 먼저 할 일.**
- [ ] T009 [US2] 미변환 자산 확인 — 원본 `images/food/*.png` 620장 중 `images/webp/food/*.webp` 가 없는 건을 뽑아 `cwebp -q 80 -m 6` 로 마저 변환한다(2026-08-04 기준 320장 남음).
- [ ] T010 [US2] 백필 UPDATE 실행(quickstart §3) — `WHERE image_ref LIKE 'images/food/%.png'` 로 멱등. T009 없이 먼저 실행하면 그 음식들이 빈 이미지가 된다.
- [ ] T011 [US2] 검증 쿼리 — `images/food/%.png` 잔여 0건, `images/webp/food/%.webp` 건수 일치.
- [ ] T012 [US1] 배포 직전 백필 재실행 — 배포 전까지 생성되는 신규 음식은 여전히 png 경로로 기록되므로 한 번 더 돌려야 수렴한다.

---

## Phase 5: 전환 후 정리 (T008 통과 시)

- [ ] T013 [P] Lambda `convert-food-image-png-to-webp` + Pillow 레이어 + 실행 롤 삭제.
- [ ] T014 [P] S3 이벤트 알림(트리거) 제거 — 버킷 속성 → 이벤트 알림.
- [ ] T015 [P] 임시 IAM 사용자 `kbap-cli` 액세스 키 비활성화/삭제.
- [ ] T016 dev 검증 — quickstart §4(배치 제출→회수→`imageUrl` 이 `.webp`→앱 렌더링·화질 확인).
- [ ] T017 KB-285 DoD 갱신 — 방향 전환(R6)과 실측 수치, 백필 일시·건수를 이슈에 남긴다.

기존 `images/food/*.png` 620장은 삭제하지 않는다 — 지워도 얻는 게 없고 되돌릴 여지만 없앤다.

---

## Dependencies & Execution Order

- **T008 이 전체의 관문이다.** 미지원이면 Phase 3 코드를 revert 하고 Lambda 구조로 돌아간다.
- US2(T009→T010→T011) 순서 엄수. T010 을 먼저 하면 미변환 음식이 빈 이미지가 된다.
- Phase 5 는 T008 통과 + 배포 이후. 배포 전에 Lambda·트리거를 지우면 그 사이 생성되는 png 가 변환되지 않는다.
- T012 는 배포 직전(같은 창)에 실행한다.

## Notes

- 코드 변경은 5개 파일 — `LlmModelProperties`·`OpenAiFoodImageBatchClient`(+테스트)·`FoodImageBatchCollectService`(+테스트)·`application.yml`. Flyway 마이그레이션 없음.
- `image_batch_item.file_name` 은 계속 **실제 업로드 키**다(이제 webp). put 키와 어긋나면 재시도 때 고아 객체가 쌓인다.
- 관리자 화면에서 운영자가 직접 입력하는 `imageRef` 는 이 규칙 대상이 아니다(입력값 그대로 저장).
