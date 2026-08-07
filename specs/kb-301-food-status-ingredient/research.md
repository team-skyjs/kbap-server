# Research: food 상태 enum 간소화 및 ingredients 개명 (KB-301)

## R1. fail 상태 명칭

- **Decision**: `FAILED`
- **Rationale**: 랭체인 pass/fail 어휘 직결. REVIEW 계열(`REVIEW_REQUIRED` 등)은 승인 축 `PENDING_REVIEW` 와 혼동. 구 `REVIEW_REJECTED` 재사용은 의미가 달라(반려 2회 종착 vs 검수 탈락) 기각. (2026-08-08 사용자 확정)
- **Alternatives considered**: `REJECTED`(관리자 반려로 오독), `REVIEW_REQUIRED`(PENDING_REVIEW 충돌).

## R2. 배치 콘텐츠 잡 처리 — 전량 주석 처리 (2026-08-08 사용자 결정)

- **Decision**: 프롬프트로 콘텐츠를 채우는 코드는 더 이상 필요 없지만 **삭제하지 않고 빌드 오류를 피하는 수준으로 전부 주석 처리**해 남긴다. 대상: `batch/content/*` 5파일 + 그 테스트, 그리고 구 상태(INCOMPLETE 등)를 참조해 컴파일이 깨지는 `:common` 의 배치 전용 코드(`FoodJpaRepository` 벌크 전이·카운트 쿼리, `Food` 의 `needs*`/`transitionByContentState` 등). 각 주석 블록 상단에 사유 한 줄(KB-301 파이프라인 이관·KB-302 정리 예정)을 남긴다.
- **Rationale**: 잡의 루프(INCOMPLETE 읽기 → 채움 → 완성도 판정 전이)는 4상태 모델에서 표현 불가. 다만 랭체인 이관 안정화 전까지 참고·복구 가능성을 위해 코드는 보존한다(사용자 지시). 최종 삭제는 KB-302 에서.
- **Alternatives considered**: (a) 삭제 선행 — 사용자 기각(일단 남겨둘 것). (b) FAILED 재대상화로 살려두기 — FR-003 위반, 기각.
- **잔여**: `:batch` 모듈은 진입점+config 만 활성인 부팅 가능한 상태로 남는다. Kotlin 주석 금지 컨벤션의 예외로 취급(사용자 명시 지시 — 보존 목적의 전량 주석).

## R3. 신규 스캔 센티널의 시작 상태

- **Decision**: 스캔이 생성하는 미수집 음식 행(`Food.incomplete()` → `Food.failed()` 로 개명)은 **FAILED 로 시작**한다.
- **Rationale**: 승인된 매핑(FR-005)이 기존 INCOMPLETE→FAILED 이므로 신규 행도 동일 의미로 시작해야 일관. 센티널 규약(미조사 vs 없음 구분)과 스캔 UNKNOWN 위험도 표시는 상태 무관하게 유지된다. KB-302 에서 랭체인 결과가 이 행들을 pass/fail 로 덮는다.
- **Alternatives considered**: 별도 대기 상태 신설 — 사용자가 명시 기각(스캔 표시와 파이프라인은 별개 프로세스).

## R4. MySQL ENUM 컬럼 변경 전략

- **Decision**: 상태 변경은 3단계 — (1) ENUM 을 신구 합집합으로 확장 MODIFY, (2) 매핑 UPDATE(REVIEWED→PENDING_REVIEW, REVIEW_REJECTED·INCOMPLETE→FAILED), (3) 최종 4값 `ENUM('FAILED','PENDING_IMAGE','PENDING_REVIEW','READY')` 로 축소 MODIFY. 개명(`avoidance_substances`→`ingredients` RENAME COLUMN + `avoidance_substance`→`ingredients` RENAME TABLE)은 **별도 마이그레이션 파일**로 분리한다(tasks 단계 조정 — US1/US2 스토리 독립 구현·순서 독립 규약 부합).
- **Rationale**: MySQL 은 ENUM 정의에 없는 값이 행에 있으면 축소 MODIFY 가 실패하므로 확장→UPDATE→축소 순서가 필수. 1단계의 구 값(INCOMPLETE·REVIEWED·REVIEW_REJECTED)은 기존 행 이관용 일시 정의이고 3단계에서 사라진다. 타임스탬프 버전·독립 실행 규약 준수.
- **DEFAULT 제거 (2026-08-08 사용자 지적)**: 구 스키마의 `DEFAULT 'READY'` 를 복원했다가 폐기했다. 새 모델에서 READY 는 사용자 노출 상태라 상태를 빠뜨린 INSERT 가 콘텐츠 없는 음식을 즉시 노출시킨다(fail-open). 프로덕션 raw INSERT(`upsertIncomplete`)는 상태를 명시하므로 기본값에 의존하는 코드가 없고, 기대던 테스트 시드 3곳은 의도를 명시하도록 고쳤다. `FAILED` 기본값 대신 **무기본값**을 택한 이유: 상태는 파이프라인이 결정하는 값이라 암묵값이 있으면 안 되고, 누락 시 MySQL 이 즉시 에러를 내 진단이 명확하다.
- **Alternatives considered**: VARCHAR 전환 — 기존 스키마가 ENUM 이라 일관 유지, 기각.

## R5. ingredients 명칭 계열

- **Decision**: DB 컬럼 `ingredients`(2026-08-08 사용자 확정 — 음식의 재료 목록이므로 복수). 엔티티 필드 `ingredients`, 값 타입 `FoodAvoidanceItem` → `FoodIngredient`, API 응답 필드 `ingredients` 로 전 계층 일관 개명. `avoidanceSubstancesByProbability()` → `ingredientsByProbability()`.
- **Rationale**: 이 데이터는 음식의 재료 목록이지 기피성분이 아니다 — 기피는 회원 프로필이 이 코드를 참조할 때만 생기는 의미다. 저장·코드·응답 전 계층을 복수 `ingredients` 로 통일한다. 카탈로그 테이블도 `ingredients` 라 이름이 겹치지만 대상이 달라(테이블 vs food 컬럼) 충돌하지 않는다.
- **Alternatives considered**: 컬럼만 단수 `ingredient` — 최초 지시를 문자 그대로 따랐다가 사용자 정정으로 폐기(목록 의미가 드러나지 않음).

## R7. 성분 카탈로그 개명 (2026-08-08 사용자 확정 — 2회 정정 반영)

- **Decision**: 카탈로그(81종)를 **재료 개념으로 전면 개명**한다 — 테이블 `avoidance_substance` → **`ingredients`**, 패키지 `common.domain.avoidance` → **`common.domain.ingredient`**, 엔티티 `AvoidanceSubstance` → **`Ingredient`**, 코드 enum `AvoidanceSubstanceCode` → **`IngredientCode`**, 리포지토리 → `IngredientJpaRepository`. 회원 쪽 참조 값타입 `AvoidanceSubstanceCodeRef` → `AvoidedIngredientCodeRef`(내부 전용).
- **Rationale**: 이 카탈로그는 음식의 재료 사전이다. 기피는 **회원 프로필이 그 코드를 참조할 때만** 생기는 관계 의미이므로, 카탈로그 자체의 어휘에 기피를 섞지 않는다(사용자 지적).
- **유지 대상 — 회원의 기피 설정**: `member.avoidance_substance_codes` 컬럼과 `avoidanceSubstanceCodes` 필드는 그대로 둔다. 후자는 온보딩·프로필 **요청·응답의 클라이언트 계약 필드**라 개명하면 앱이 깨진다. 의미도 "회원이 기피하는 재료 코드"로 정확하다.
- **경계 강제**: ArchUnit 도메인 허용 맵의 `"avoidance"` 키를 `"ingredient"` 로 함께 갱신했다(`ModuleBoundaryTest`).
- **주의**: 적용 완료된 시드 마이그레이션(`V2026.07.16.21.38.42__seed_avoidance_catalog.sql`)은 수정 금지 — 신규 마이그레이션에서 RENAME 만 수행한다. 시드-동기화 테스트는 `IngredientCatalogSeedSyncTest` 로 개명했고 리소스 경로는 그대로다.
- **Alternatives considered**: 저장 명칭만 바꾸고 클래스·enum 유지 — 최초 결정이었으나 "엔티티명도 바꿔라" 는 사용자 지시로 폐기(엔티티가 `Ingredient` 인데 코드 enum 이 `AvoidanceSubstanceCode` 면 같은 불일치가 남는다).

## R6. 승인 플로우 재배선 (구 검수 메서드 대체)

- **Decision**: `passContentReview`(PENDING_REVIEW→REVIEWED) → **`approve`**(PENDING_REVIEW→READY). `rejectContentReview`(필드 초기화+재시도 카운트+INCOMPLETE 회귀) → **`reject`**(PENDING_REVIEW→FAILED, 사유 기록만 — 필드 초기화 폐기, 반려 횟수는 관리자 참고용으로 누적). `attachImage` 는 PENDING_IMAGE→PENDING_REVIEW 고정 전이.
- **구현 시 조정**: FAILED→PENDING_IMAGE 전용 메서드(`resubmit`)는 프로덕션 호출부가 없어 두지 않았다 — 관리자는 음식 수정 폼에서 상태를 직접 지정한다. KB-302 가 전용 버튼을 붙일 때 도입한다.
- **Rationale**: 배치 재채움이 없으므로 반려 시 필드를 비울 이유가 없다(데이터 보존). READY 승격이 별도 수동 단계였던 REVIEWED 를 접고 승인=READY 로 직결(스펙 FR-002). `content_review_attempts`·`content_review_rejection_reason` 컬럼은 관리자 참고 정보로 유지(스펙 Assumption).
- **Alternatives considered**: REVIEWED 유지(승인 2단계) — 스펙이 4상태로 확정, 기각.
