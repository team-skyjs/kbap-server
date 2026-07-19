# Phase 0 Research: 서비스 조회 메서드 네이밍 get 통일

명세에 NEEDS CLARIFICATION 이 남아 있지 않으므로(모두 대화에서 해소), 본 문서는 리네임 결정·순서·충돌 회피를 고정한다.

## Decision 1: 조회 계약과 이름 매핑

- **Decision**: 조회는 `get~`(없으면 `BusinessException`/non-null)으로 통일. null 이 도메인상 정상값인 단건만 `get~OrNull`(반환 `T?`). 컬렉션은 `get~`(빈 값 허용), 페이지는 `get~Page`(반환 `~Page`). `find` 접두 폐기.
- **Rationale**: 이름으로 "없을 때 예외인지 null 인지"를 예측 가능하게. 기존 단건 get/find 계약은 이미 지켜지므로 컬렉션·페이지의 혼용만 정리하면 규약이 단순해진다.
- **Alternatives considered**:
  - 전부 non-null `get~` 통일(OrNull 폐지) → 게스트 회원·refresh 부재 등 null 이 정상 흐름인 호출부가 예외로 깨지고 refresh 는 에러 코드(INVALID_REFRESH_TOKEN)까지 달라져 **불가**.
  - `find` 를 nullable 전용으로 유지 → 혼용이 남고 규약이 두 접두를 계속 안고 감. 폐기가 더 단순.

## Decision 2: `findActive` 의 get/getOrNull 분리

- **Decision**: `findActive`(nullable) → `getMemberOrNull`, private `findActiveOrThrow` → public `getMember`(throw). 호출부는 계약별로 배분:
  - 게스트 정상: `getAvoidedCodes`·`HomeApplicationService` → `getMemberOrNull`
  - refresh 부재 = INVALID_REFRESH_TOKEN: `AuthApplicationService.refresh` → `getMemberOrNull == null`
  - withdraw 부재 = MEMBER_NOT_FOUND: `AuthApplicationService.withdraw` + 내부 4곳 → `getMember`
- **Rationale**: 회원 부재가 "정상(게스트)"과 "에러"로 갈리고 에러 코드도 호출부마다 달라, 단일 throw 로 합칠 수 없다.
- **Alternatives considered**: 단일 `getMember`(throw)만 + 호출부 try/catch → 예외를 제어 흐름에 쓰는 안티패턴, refresh 에러 코드도 오염. `getActiveMemberOrNull`/`getActiveMember`(active 조건을 이름에 보존) → MemberService 의 모든 조회가 예외 없이 active 필터를 걸므로 "MemberService 조회는 항상 active 회원만 노출한다"를 **서비스 계약으로 규약화**(CLAUDE.md, T010)하고 짧은 이름을 택했다.

## Decision 3: `findVerifiedImage` 를 검증 행위로 재분류

- **Decision**: `verifyImageAccess(memberId, path)` 로 명명. 검증 내용은 (a) `uploaded_image` 기록 존재(=completeUpload 로 Content-Type·크기 검증 통과) + (b) 요청 회원 소유. 반환 타입·읽기전용 트랜잭션·미사용/TODO 주석은 그대로 유지.
- **Rationale**: 이 메서드는 조회가 아니라 "접근 가능 여부 검증"이라 get/find 규약 밖이 자연스럽다. ScanService 배선(TODO) 활성화는 동작 추가라 이번 범위 밖.
- **Alternatives considered**: `getVerifiedImage`(KB-170 이슈 표 원안) → 조회로 오분류되고 get 계약(없으면 throw)과 반환(`T?`)이 어긋남. `verify~` 가 정확.

## Decision 4: 리네임 순서 — 이름 충돌 회피

FoodService 에서 기존 `search`/`searchFoodPage` 가 목표 이름과 겹치므로 **로더를 먼저** 개명한다:

1. `searchFoodPage`(로더, List) → `getFoodsByKeyword` (internal)  ← 먼저
2. `search`(FoodPage) → `searchFoodPage`                           ← 그다음
3. `findFoodPage`(로더, List) → `getFoods` (internal)
4. `browse`(FoodPage) → `getFoodPage`

- **Rationale**: 2번을 1번보다 먼저 하면 `searchFoodPage` 이름이 잠깐 중복돼 컴파일 실패. 로더 개명 선행으로 회피.
- **로더 이름도 `get~s` 컬렉션 규칙으로 통일**한다(초안의 `load~Page` 는 "Page 접미 + List 반환" 불일치를 재생산해 폐기). internal 로더라는 사실은 가시성 키워드가 표현하므로 별도 접두(`load`)를 만들지 않는다. `getFoodPage`(public, FoodPage)와 `getFoods`(internal, List)는 이름·시그니처 모두 구분된다.
- **나머지 서비스**는 이름 충돌이 없어 순서 무관.

## Decision 5: 동작 계약이 바뀌는 유일 지점 — `getReadyFood`

- **Decision**: `findReadyById`(`Food?`) → `getReadyFood`(`Food`, 내부에서 `?: throw FOOD_NOT_FOUND`). 두 호출부(`getDetail`·`BookmarkService.bookmark`)는 이미 동일 예외를 던지므로 최종 동작 동일.
- **Test-First**: 해당 테스트(`FoodServiceTest` 의 `shouldBe null`/`shouldBeNull` 3곳)를 `shouldThrow<BusinessException>` 로 먼저 바꿔 Red 확인 후 예외를 메서드로 이동. `shouldNotBeNull()` 7곳은 반환값 직접 사용으로 정리.
- **Rationale**: 계약 이동이 있는 유일한 조회라 원칙 I(Red 우선)을 명시적으로 적용.

## 범위 밖 (확정)

- `:app:batch`, `FoodScoringSource.nextChunk`(순차 공급 `next~`)
- `LlmCallCostService.record`, 행위 메서드(CRUD·도메인 동사), 보조(`count/is/has/exists`)
- `getDetail`→`getFoodDetail` 명확화는 선택(필수 완료 기준 아님)
- DB 스키마·Flyway·엔티티 구조·모듈 그래프
