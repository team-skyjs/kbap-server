---

description: "Task list for kb-285 음식 사진 WebP 변환본 서빙"
---

# Tasks: 음식 사진 WebP 변환본 서빙

**Input**: Design documents from `/specs/kb-285-food-image-webp/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First는 **NON-NEGOTIABLE**(헌법 원칙 I). US1 은 실패 테스트를 먼저 쓰고 Red 를 확인한 뒤 구현한다. US2·US3 은 운영 작업(코드 변경 없음)이라 코드 테스트 대상이 아니며, 대신 실행 전후 검증 쿼리·확인 절차를 태스크로 둔다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 의존 없음)
- **[Story]**: US1 / US2 / US3

## Phase 1: Setup

**해당 없음** — 신규 의존성·모듈·설정이 없다. 브랜치·워크트리(`kb-285-food-image-webp`)는 `/speckit-specify` 에서 생성 완료.

## Phase 2: Foundational

**해당 없음** — 스키마 변경·공통 인프라 코드가 없다. 각 스토리는 서로 다른 대상(코드 / 운영 DB / AWS)이라 선행 차단 요소가 없다.

---

## Phase 3: User Story 1 - 신규 음식 사진이 가벼운 포맷으로 내려온다 (Priority: P1) 🎯 MVP

**Goal**: 회수기가 S3 에는 PNG 원본을 그대로 올리고 `food.image_ref` 에는 webp 변환본 경로를 기록한다.

**Independent Test**: `./gradlew :api:test --tests "com.kbap.api.food.FoodImageBatchCollectServiceTest"` — 회수 후 `imageRef` 가 `images/webp/food/….webp`, put 키와 `item.fileName` 은 `images/food/….png`.

### Tests for User Story 1 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/food/FoodImageBatchCollectServiceTest.kt` 에 `WEBP_REF_PATTERN = Regex("""^images/webp/food/[0-9a-f]{12}_[0-9a-f]{16}\.webp$""")` 을 추가하고, "회수 — completed 배치" 의 성공 케이스 기대값을 분리한다: put 키·`item.fileName` 은 `FOOD_IMAGE_KEY_PATTERN`(png) 유지, `reloaded.imageRef` 는 `WEBP_REF_PATTERN` 매칭 + `webpRefOf(key)` 와 일치. 실행해 **Red 확인**.
- [X] T002 [US1] 같은 파일의 나머지 `imageRef` 단언을 webp 기준으로 맞춘다 — "텍스트 미완(INCOMPLETE) 음식" 케이스의 `imageRef` 패턴, "이미지 재생성" 케이스의 `firstKey`/`secondKey`(webp ref)와 `fakeStorage.heads`(png 키) 분리, "put 이후 트랜잭션 실패분 재시도" 케이스의 예약 키(png) 대비 `imageRef`(webp) 기대값. 실행해 **Red 확인**.
- [X] T003 [P] [US1] 같은 파일의 "저장 키 생성 규칙" given 에 `webpRefOf` 매핑 테스트를 추가한다 — `webpRefOf("images/food/abc_def.png")` 이 `images/webp/food/abc_def.webp` 가 되고, 파일명(sha12_uuid16)이 원본과 동일하게 보존됨. 실행해 **Red 확인**(함수 미존재 컴파일 실패).

### Implementation for User Story 1

- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodImageBatchCollectService.kt` 의 companion 에 `storageKeyOf` 옆으로 `fun webpRefOf(pngKey: String): String` 를 추가한다 — 접두 `images/food/` → `images/webp/food/`, 확장자 `.png` → `.webp`.
- [X] T005 [US1] 같은 파일 `handleResult` 에서 `food.attachImage(key)` 를 `food.attachImage(webpRefOf(key))` 로 바꾼다. `storageObjectStore.put(key, bytes, "image/png")`·`item.done(key)`·`reserveFileName` 은 **그대로 둔다**(png 예약 계약 유지).
- [X] T006 [US1] `./gradlew :api:test --tests "com.kbap.api.food.FoodImageBatchCollectServiceTest"` Green 확인.
- [X] T007 [US1] `./gradlew :api:test` 전체 회귀 — 회수 경로의 `imageRef` 를 가정하는 다른 테스트(admin 배치 조회 등)가 깨지지 않는지 확인.

**Checkpoint**: 코드 변경 완료. 이 시점 배포 시 신규 회수분부터 webp 경로로 기록된다(→ 인프라 선행 조건은 Dependencies 참조).

---

## Phase 4: User Story 3 - 변환 파이프라인과 실패 인지 (Priority: P3, 배포 선행 필수)

> 우선순위는 P3(사용자 가치 간접)이지만 **US1 배포보다 먼저 완료돼야** 신규 음식의 webp 경로가 실제 객체를 가리킨다. 저장소 코드 변경 없음 — AWS 콘솔/CLI 작업.
>
> **선행 조건**: 이 페이즈의 모든 작업은 quickstart.md §0 대로 **팀 계정(`118178010621`, `ap-northeast-2`)** 임을 확인한 세션에서만 실행한다. 로컬 `aws` CLI 의 `default` 프로필은 다른 계정이고, `kbap-prod-deployer` 프로필도 배포 전용이라 Lambda·IAM 생성 권한이 없을 수 있다 — 콘솔 경로(§0-3) 권장.

**Goal**: `images/food/` PNG 저장 시 `images/webp/food/` 에 동일 파일명 webp 가 생기고, 변환 실패를 운영자가 인지한다.

**Independent Test**: 임의 PNG 를 `images/food/` 에 올리고 몇 초 뒤 `images/webp/food/` 에 같은 파일명 `.webp` 가 생겼는지, 변환 불가 파일로는 알림이 오는지 확인.

- [ ] T008 [US3] `specs/kb-285-food-image-webp/quickstart.md` §2 표대로 변환 Lambda 를 배포한다 — 트리거 prefix `images/food/` + suffix `.png`, 출력 `images/webp/food/{동일 파일명}.webp`, 리사이즈 없음, Content-Type `image/webp`.
- [ ] T009 [US3] Lambda 실행 롤을 최소 권한으로 제한한다 — `s3:GetObject` on `images/food/*`, `s3:PutObject` on `images/webp/food/*` 만.
- [ ] T010 [P] [US3] 변환 실패 인지 수단을 설정한다 — Lambda DLQ 또는 `Errors` 지표 CloudWatch 알람.
- [ ] T011 [US3] PNG 1장을 `images/food/` 에 직접 올려 변환본 생성·해상도 동일·용량 감소를 확인하고, 손상 파일로 실패 알림이 도달하는지 확인한다.

**Checkpoint**: 신규 저장분의 변환 경로가 살아 있다 — US1 배포 가능.

---

## Phase 5: User Story 2 - 이미 쌓인 음식 사진도 가벼워진다 (Priority: P2)

**Goal**: 기존 `image_ref` 를 webp 경로로 일괄 갱신한다(Flyway 아님, 운영 DB 직접).

**Independent Test**: 갱신 전후 카운트 쿼리(quickstart §3)와, 표본 음식의 상세 API `imageUrl` 이 `.webp` 로 끝나고 이미지가 렌더링되는지 확인.

- [ ] T012 [US2] 대상 건수를 확인한다 — `SELECT COUNT(*) FROM food WHERE image_ref LIKE 'images/food/%.png';` (quickstart §3).
- [ ] T013 [US2] **기존 PNG 의 변환본을 먼저 생성한다** — Lambda 는 신규 PutObject 만 트리거하므로 T008 이전 적재분은 변환본이 없다. S3 Batch Operations(Lambda 호출) 또는 기존 키 재-put 으로 `images/webp/food/` 를 채우고, 표본 몇 건을 head 로 존재 확인한다.
- [ ] T014 [US2] 백필 UPDATE 를 실행한다(quickstart §3) — `WHERE image_ref LIKE 'images/food/%.png'` 조건으로 멱등. T013 없이 먼저 실행하면 이미지가 비어 보이므로 순서 준수.
- [ ] T015 [US2] 검증 쿼리 2건 실행 — `images/food/%.png` 잔여 0건, `images/webp/food/%.webp` 건수가 T012 값 이상.

**Checkpoint**: 전체 카탈로그가 webp 로 서빙된다.

---

## Phase 6: Polish & 검증

- [ ] T016 dev 환경 end-to-end 검증 — quickstart.md §4 절차대로 배치 제출→회수→상세 API `imageUrl` 이 `.webp`→앱 렌더링·전송량 감소 확인.
- [ ] T017 [P] KB-285 DoD 체크박스를 실제 결과로 갱신하고, 백필 실행 일시·대상 건수를 이슈 코멘트로 남긴다.

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup·Foundational: 없음.
- **US1(코드)** 과 **US3(인프라)** 는 서로 다른 대상이라 **동시에 진행 가능**하다. 단 **배포 순서는 US3 → US1** — Lambda 가 없으면 신규 음식의 `image_ref` 가 존재하지 않는 객체를 가리킨다.
- **US2(백필)** 는 US3 완료 후에만 의미가 있다(T013 → T014 순서 엄수).
- Polish(T016·T017)는 US1·US2·US3 완료 후.

### Within User Story 1

- T001·T002 → (Red 확인) → T004·T005 → T006 → T007. T003 은 T001·T002 와 같은 파일이지만 독립 given 블록이라 병렬 작성 가능([P]), 실행은 함께.
- 테스트가 실제로 실패하는 것을 확인하기 전에 T004·T005 를 시작하지 않는다(헌법 I).

### Parallel Opportunities

- US1(코드)과 US3(AWS 설정)은 다른 사람이 동시에 진행 가능.
- T010(알림 설정)은 T008·T009 와 별개 리소스라 병렬.

---

## Implementation Strategy

### MVP (US1 + US3)

1. US3 T008~T011 로 변환 파이프라인을 세운다.
2. US1 T001~T007 로 기록 경로를 바꾼다(Red → Green).
3. dev 배포 후 T016 으로 신규 음식 1건 검증 → 여기까지가 최소 배포 단위.

### Incremental

4. US2 T012~T015 로 기존 적재분 백필 → 전체 카탈로그 개선.
5. T017 로 이슈 마감.

---

## Notes

- 코드 변경은 파일 2개(`FoodImageBatchCollectService.kt`, `FoodImageBatchCollectServiceTest.kt`)로 끝난다. Flyway 마이그레이션을 추가하지 않는다.
- `image_batch_item.file_name` 은 계속 PNG 키다 — 재시도 시 예약 키와 put 키가 어긋나면 고아 객체가 쌓인다.
- 관리자 화면에서 운영자가 직접 입력하는 `imageRef` 는 이번 규칙 대상이 아니다(입력값 그대로 저장).
- 작업/논리 단위마다 커밋한다.
