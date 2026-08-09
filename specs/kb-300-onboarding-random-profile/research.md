# Phase 0 Research: 온보딩 시 닉네임·프로필 사진 랜덤 지정

## R1. 구버전(1.0.0) 앱과의 공존 수단

**Decision**: 새 온보딩을 **`POST /api/v2/members/me/onboarding`** 로 열고, v1 온보딩은 그대로 둔다. 앱 버전 식별 장치를 도입하지 않는다.

**Rationale**: 이 저장소는 이미 같은 문제를 같은 방법으로 푼 선례가 있다 — `ApiPaths.V2` 상수와 `MemberV2Controller`(`PATCH /api/v2/members/me/profile`, KB-297 국적 변경 불가). 클라이언트가 호출하는 경로 자체가 버전이므로 헤더·강제 업데이트 없이 신·구가 공존하고, ArchUnit(`ModuleBoundaryTest`)의 `/api/v` 검사도 그대로 통과한다. 서버만 먼저 배포해도 구버전 앱은 v1 경로를 계속 쓰므로 영향이 0이다.

**Alternatives considered**:
- *요청 본문의 필드 유무로 분기(닉네임 없으면 랜덤)* — 하나의 엔드포인트가 두 계약을 갖게 되고, 구버전의 오타·누락이 400 대신 조용히 랜덤 지정으로 흘러 QA 에서 안 드러난다. 기각.
- *`X-App-Version` 헤더 도입* — 헤더 미전송 시 기본 동작을 정해야 하고 모든 클라이언트가 협조해야 한다. 지금 필요 없는 인프라. 기각.
- *v1 을 즉시 교체하고 강제 업데이트* — 강제 업데이트 장치가 없으므로 신규 가입이 막히는 서비스 장애. 기각(spec US2).

## R2. 랜덤 지정 로직의 소유 계층

**Decision**: `MemberService.completeOnboarding`(도메인 서비스)이 소유한다. `MemberProfileInput.nickname`·`profileImageUrl` 을 `String?`(기본 `null`)로 완화하고, **null 이면** 후보에서 추첨해 채운 뒤 `Member.completeOnboarding` 에 넘긴다.

**Rationale**: "가입자에게 어떤 기본 정체성을 부여하는가"는 표현 계층 관심사가 아니라 도메인 정책이다. 헌법 IV — 도메인 로직(정책)은 도메인 서비스가 소유하고, 리포지토리 직접 참조 허용이 로직을 소비 계층으로 옮기는 허가는 아니다. 또 도메인 서비스에 두면 v1/v2 어느 경로로 들어와도 검증(`MemberProfile.updatedWith`)을 동일하게 통과한다.

`null = 서버 지정` 규약이 암묵적이라는 점은 인정한다. 대신 v1 요청 DTO(`OnboardingRequest`)가 **non-null 코틀린 타입**을 유지하므로 v1 경로에서는 null 이 도달할 수 없고, 규약이 새는 범위가 v2 하나로 닫힌다.

**Alternatives considered**:
- *`OnboardingV2Request.toInput()` 에서 추첨* — 정책이 web DTO 로 샌다. 헌법 IV 위반. 기각.
- *`completeOnboardingWithRandomProfile(...)` 별도 서비스 메서드* — 유비쿼터스 언어가 아닌 구현 서술형 이름이고(CLAUDE.md 네이밍 규약), 본문이 기존 메서드와 90% 중복된다. 기각.
- *`MemberProfileInput` 을 v1/v2 두 타입으로 분리* — 도메인 dto 가 HTTP 버전을 알게 된다. 기각.

## R3. 상수·생성기의 표현과 위치

**Decision**: `com.kbap.common.domain.member.model.OnboardingProfileDefaults` object 하나에 다음을 둔다.

- `PROFILE_IMAGE_PATHS: List<String>` — 이미지 후보 상수(R6) + `randomProfileImagePath()` = `PROFILE_IMAGE_PATHS.random()`
- `randomNickname()` — 6자리 코드 생성(R5). 고정 후보 목록 없음.

**Rationale**: 이미지 후보는 스토리지 자산과 1:1 로 묶여 있어 목록이 맞고, 닉네임은 생성 규칙이 맞다(R5). 둘 다 운영 편집 대상이 아니라 배포 산출물이므로 DB 테이블·설정 프로퍼티로 만들 이유가 없다 — 파일 하나로 끝나고 소유 도메인 안에 있어 경계도 지켜진다. 무작위는 Kotlin 표준 라이브러리(`List.random()`·`CharArray.random()`) — `Random` 을 seam 으로 주입하지 않는다(결정론이 필요한 테스트가 없고, 주입은 구현 하나짜리 추상화다).

**Alternatives considered**:
- *DB 테이블 + Flyway 시드* — 운영 편집 요구가 없는데 마이그레이션·조회·캐시가 생긴다. 기각.
- *`application.yml` 프로퍼티* — 값이 환경별로 갈릴 이유가 없고 타입 검증도 약하다. 기각.
- *`Random` 주입 seam* — 구현 하나뿐인 추상화. 기각.

## R4. 후보 값 검증을 언제 강제할 것인가

**Decision**: 유효성은 **단위 테스트가 배포 전에** 강제한다 — 생성 닉네임: 형식 정규식 일치 + 공백 아님 + `member.nickname` 길이 상한(30) 이내, 이미지 경로 후보: 목록 비어 있지 않음 + 각 원소가 절대 URL 아님 + 선행 `/` 없음 + 512자 이내.

**Rationale**: 잘못된 값이 들어가면 온보딩 런타임에 `MemberProfile.updatedWith` 검증에 걸려 400 이 나간다 — 사용자가 아무 잘못도 하지 않았는데 가입이 막히는 최악의 실패다(spec Edge Cases). 검증 규칙은 이미 `MemberProfile` 에 있으므로 테스트가 그 규칙을 생성값·후보 전체에 한 번 돌리면 된다. 런타임 방어 코드를 새로 넣지 않는다.

**Alternatives considered**:
- *애플리케이션 기동 시 `init` 블록 검증* — 실패가 배포 후에야 드러난다(테스트보다 늦다). 기각.
- *검증 없음* — 후보 오타 하나가 전면 가입 장애. 기각.

## R5. 닉네임 생성 방식 — 고정 후보 목록이 아니라 **영문 코드 생성** (2026-08-10 변경)

**Decision**: 닉네임은 후보 목록에서 고르지 않고 **6자리 영숫자 코드**로 생성한다. 접두 단어를 붙이지 않는다. 문자 집합은 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`(혼동 문자 `0`·`O`·`1`·`I` 제외) — 예: `K7M2XB`, `9PTQR4`, `XB3NHM`. 회원 간 중복은 여전히 허용한다(회피 로직 없음).

**Rationale**:

1. **읽을 수 있어야 한다.** 주 사용자가 외국인인데 한국어 후보(`김치새내기`)를 붙이면 본인이 자기 닉네임을 읽지 못한다 — 프로필에 낯선 문자열이 자기 이름으로 붙는다.
2. **관리 자산을 만들지 않는다.** 고정 문구 목록은 누군가 취향·중의성·불쾌감 기준으로 계속 검수해야 하는 자산이 된다. 생성 규칙에는 그 유지비가 없다.
3. **중복이 사실상 사라진다.** 32^6 ≈ 10.7억 조합. SC-004 는 자동 충족이며 분산 검증이 자명해진다.
4. **"아직 안 정했다"가 드러난다.** 코드형 표기는 사용자가 닉네임을 직접 설정하도록 자연스럽게 유도한다.
5. **짧다.** 6자는 커뮤니티 작성자란·프로필 헤더 어디에도 줄바꿈 없이 들어간다.

**감수하는 비용**: 접두 단어 없는 순수 코드는 커뮤니티(리뷰·게시글·댓글) 작성자란에서 봇 계정처럼 읽힐 수 있다. 검토 시 `Foodie-K7M2` 처럼 단어를 앞에 붙이는 안을 제시했으나, **짧은 표기를 우선해 접두 없이 6자로 확정**했다(2026-08-10 사용자 결정). 되돌리려면 상수 한 줄이다.

**Alternatives considered**:
- *한국어 고정 후보 24종* — 2026-08-10 이전 결정. 위 (1)·(2) 이유로 철회.
- *영어 형용사+명사 조합 목록(`HappyKimchi42`)* — 읽기는 좋으나 단어 목록 2개를 다시 검수 자산으로 떠안는다. (2) 위반. 기각.
- *접두 단어 + 코드(`Foodie-K7M2XB`)* — 봇처럼 보이는 문제는 줄지만 13자로 길어진다. 길이 우선으로 기각.
- *닉네임 unique 보장(중복 시 재생성)* — unique 제약이 없고 사용자가 바로 바꿀 수 있는 값이다. 추첨-확인-재추첨 루프와 경합 처리를 살 이유가 없다. 기각.

**형식 불변식** (테스트가 강제): `^[A-HJ-NP-Z2-9]{6}$`, 길이 6자(컬럼 상한 30 이내), 공백 없음.

## R6. 프로필 이미지 후보 자산 — **확정**

**Decision**: 스토리지에 이미 존재하는 아바타 6종을 상수 목록에 넣는다(FR-014). 색상 이름만 다른 동일 디렉터리 자산이다.

```
images/webp/default_profile/avatar-amber.png
images/webp/default_profile/avatar-navy.png
images/webp/default_profile/avatar-olive.png
images/webp/default_profile/avatar-orange.png
images/webp/default_profile/avatar-plum.png
images/webp/default_profile/avatar-teal.png
```

**선행 `/` 는 붙이지 않는다.** 사용자가 알려준 원본 표기는 `/images/webp/default_profile/...` 이지만, `MemberProfile.validatedImagePath` 가 저장 전에 `trimStart('/')` 로 선행 슬래시를 제거한다(스토리지 키 컨벤션 = 무슬래시). 상수에 슬래시를 붙이면 저장값과 상수가 달라져 "지정된 값이 후보 목록 안에 있는가" 검증이 어긋난다. 처음부터 슬래시 없이 선언한다.

**디렉터리명이 `webp` 인데 확장자가 `.png` 인 점**은 스토리지 자산 그대로다 — 코드에서 보정하지 않는다.

**Impact**: 후보 6종이면 SC-004(한 값 점유율 30% 미만)가 기대값 16.7%로 여유 있게 성립한다. 기존 기본 이미지(`images/default/profile/profile-default-512.png`)는 후보에 넣지 않는다 — v1 클라이언트가 명시 전송하는 값이지 랜덤 아바타가 아니다.

## R7. v1 온보딩의 향후 처리

**Decision**: 이번 작업에서 v1 온보딩을 삭제하지도, 동작을 바꾸지도 않는다. swagger 설명에 "구버전 앱 전용" 안내만 덧붙이는 선에서 끝낸다(선택).

**Rationale**: 1.0.0 사용률이 남아 있는 동안 v1 은 유일한 가입 경로다. 제거는 사용률 지표를 보고 별도 티켓으로 판단할 일이다(spec Assumptions).

**Alternatives considered**:
- *v1 을 `@Deprecated` 로 컴파일 경고화* — 서버 내부 경고일 뿐 클라이언트에 전달되지 않는다. 실익 없음. 기각.
- *v1 이 내부적으로 v2 를 호출하도록 통합* — 계약이 다른 두 경로를 억지로 합치는 것. 회귀 위험만 늘어난다. 기각.
