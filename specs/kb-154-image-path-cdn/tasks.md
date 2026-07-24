# Tasks: 이미지 참조는 CDN 도메인 없이 경로만 저장하고 응답 조립 시 조합

**Input**: `specs/kb-154-image-path-cdn/` (spec·plan·research·data-model·contracts)
**Tests**: 헌법 원칙 I(Test-First, NON-NEGOTIABLE) — 모든 구현은 실패 테스트(Red) 확인 후 진행

## Phase 1: Setup

없음 — 신규 모듈·의존성·마이그레이션 0.

## Phase 2: Foundational (`ImageUrls` — 전 스토리 공유)

- [X] T001 [Red] `core/src/test/kotlin/com/kbap/core/image/ImageUrlsTest.kt` 신규 — resolve 규칙 단위 검증: null→null, `http(s)://` 시작→그대로(대소문자 무시), base 빈 값→ref 그대로, base 후행 `/`·ref 선두 `/` 슬래시 정규화 접합, base 교체 시 결과 도메인 변경(US3 성질). 실행해 컴파일 실패(Red) 확인
- [X] T002 [Green] `core/src/main/kotlin/com/kbap/core/image/ImageUrls.kt` 신규 — Spring-free `object ImageUrls { fun resolve(base: String, ref: String?): String? }`. `./gradlew :core:test` Green 확인

## Phase 3: US1 — 프로필 사진 경로 저장 + 완전 URL 응답 (P1) 🎯 MVP

- [X] T003 [Red] [US1] `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt` 수정 — 사진 검증 케이스 교체: 경로 허용, `http://`·`https://`·`HTTPS://` 시작 거부(MEMBER-008), 빈 문자열→null(제거), 512 초과 거부, `allowedImageHosts` 파라미터 제거 반영. Red 확인
- [X] T004 [Red] [US1] `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt` 보강 — 온보딩/수정에 경로 전송→200·조회 응답이 `https://cdn.test/{경로}` 조합 URL, 전체 URL 전송→400 MEMBER-008, 미등록 조회→null, 레거시 절대 URL 저장 상태 조회→그대로 반환. Red 확인
- [X] T005 [US1] `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfile.kt` — `validatedImageUrl`→`validatedImagePath`(URI/호스트 검증 삭제), `updatedWith`·`of` 에서 `allowedImageHosts` 제거; `model/Member.kt` — `completeOnboarding`·`updateProfile` 시그니처에서 `allowedImageHosts` 제거
- [X] T006 [US1] `domain/member/src/main/kotlin/com/kbap/domain/member/MemberService.kt` — `profile-image-allowed-hosts` @Value 제거, `@Value("\${kbap.storage.public-base-url:}")` 추가, `getMyProfile` 에서 `ImageUrls.resolve` 조립; `dto/MyProfileResult.kt` — `of(...)` 에 resolved URL 전달
- [X] T007 [P] [US1] 부수 정리 — `core/.../error/ErrorCode.kt` MEMBER-008 메시지를 경로 기준 문구로, `app/api/.../member/MemberApi.kt` Swagger 설명·예시를 경로 입력으로, `app/api/src/main/resources/application-{prod,staging}.yml` 에서 `profile-image-allowed-hosts` 제거, `app/api/src/test/kotlin/com/kbap/app/api/member/ProfileImageHostRestrictionTest.kt` 삭제
- [X] T008 [US1] Green 확인 — `./gradlew :domain:member:test :app:api:test --tests "com.kbap.app.api.member.*"` 통과

**Checkpoint**: US1 단독으로 배포 가능(MVP) — 프로필 경로 저장·조합 응답 완성.

## Phase 4: US2 — 음식 이미지 완전 URL 응답 (P2)

- [X] T009 [Red] [US2] food·home 통합 테스트 보강 — `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailControllerTest.kt`(상세)·`FoodListControllerTest.kt`(목록) 에 imageRef 경로 시드→응답 `https://cdn.test/{경로}` 검증, `app/api/src/test/kotlin/com/kbap/app/api/home/HomeControllerTest.kt` 에 popularFoods imageRef 조합 검증. `FoodTestSeed`/`HomeTestSeed` 에 imageRef 경로 세팅. Red 확인
- [X] T010 [US2] `domain/food/src/main/kotlin/com/kbap/domain/food/dto/FoodSummaryView.kt` — `from(food, lang, codes, imageUrl)` 파라미터 추가; `FoodService.kt` — @Value 베이스 주입, `getDetail`·`foodPage` 에서 resolve; `application/src/main/kotlin/com/kbap/application/home/HomeApplicationService.kt` — @Value 베이스 주입, `FoodSummaryView.from` 호출부 resolve 전달
- [X] T011 [US2] Green 확인 — `./gradlew :domain:food:test :application:test :app:api:test --tests "com.kbap.app.api.food.*" --tests "com.kbap.app.api.home.*"` 통과

**Checkpoint**: US3(도메인 교체=설정 1곳)은 T001 의 base 교체 케이스 + US1·US2 구조로 충족 — 별도 구현 없음.

## Phase 5: Polish

- [X] T012 전체 게이트 — `./gradlew build` (ArchUnit 포함) 통과, quickstart.md §1 명령 재확인, 시나리오 테스트(`scenario` 태그) 회귀 확인

## Dependencies

- T001→T002 (Foundational) → US1(T003~T008) → US2(T009~T011) → T012
- US1·US2 는 상호 독립이나 둘 다 T002(`ImageUrls`)에 의존. T007 은 T005·T006 과 병렬 가능 [P]
- US3 은 별도 태스크 없음(T001 케이스로 검증)

## Implementation Strategy

MVP = Phase 2+3 (프로필 — 티켓 핵심 리스크 해소). US2 는 조립 지점 추가만이라 저위험 후속. 전 태스크 단일 에이전트 순차 실행(사용자 지시), Red 확인을 건너뛰지 않는다.
