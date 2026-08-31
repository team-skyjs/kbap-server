# Research: 스프링 모듈 구조 다이어트 (kb-244)

**Date**: 2026-07-28 | **Spec**: [spec.md](spec.md)

## 현재 모듈 인벤토리 (settings.gradle.kts 실측)

16개 모듈: `:core`, `:domain:{food,member,avoidance,review,scan,bookmark,image,metering}`(8),
`:application`, `:infra:{llm,auth,redis,storage}`(4), `:app:{api,batch}`(2). (+`buildSrc`)

CLAUDE.md 요약(도메인 5종·인프라 3종)보다 커진 상태 — bookmark·image·metering·storage 가 추가됐고
`:domain:research` 는 settings 에 없다(디렉터리만 잔존). `:domain:review` 는 빈 placeholder.

의존 실측(각 build.gradle.kts):

- `:app:batch` → `:infra:llm`, `:infra:storage`, `:domain:food`, `:domain:avoidance` (+전이: food →api→ member →api→ avoidance, core)
- `:app:api` → application, core, 도메인 6종(member·food·scan·bookmark·image·metering), infra 4종(auth·storage=implementation, llm·redis=runtimeOnly)
- `:infra:auth` → `:application`(TokenIssuer·TokenParser·SocialTokenVerifier seam), `:domain:member`(SocialAccountDeleter seam), `:core`
- `:infra:redis` → `:application`(RefreshTokenStore seam)
- `:infra:storage` → `:core`(StorageObjectStore seam), `:application`(PresignedUploadPort seam)
- `:infra:llm` → `:core`(ScannedNameInterpreter seam)
- 도메인 간: scan →api→ food·member, →impl→ image / food →api→ member·avoidance / member →api→ avoidance

## Decision 1 — 목표 모듈 구성: 3 앱/공유 + infra 4종 유지

**Decision**: 최종 구성은 `:common`·`:app:api`·`:app:batch` + `:infra:{llm,auth,redis,storage}` (+buildSrc), 총 7개.

**Rationale**: 사용자 지시 — 인프라 모듈은 제거하지 않는다. 인프라는 외부 시스템(SDK 의존)별 격리가
빌드 시간·의존 오염 방지에 실익이 있고, seam 패턴(인터페이스는 소비 계층·구현은 infra·조립은 부트앱)이
이미 안정적이다. 다이어트 대상은 실이득 없이 쪼개진 도메인 8종 + core + application(10개 → 2개 흡수).

**Alternatives considered**: (a) 문자 그대로 3모듈(인프라까지 흡수) — 사용자가 명시 거부, SDK 의존이
api/common 에 오염됨. (b) 현행 유지 — 스펙이 부정한 상태.

## Decision 2 — 모듈별 목적지 매트릭스

**Decision**: 컴파일 의존이 강제하는 최소 공유 집합만 common 으로:

| 현재 모듈 | 목적지 | 근거 |
|-----------|--------|------|
| `:core` | `:common` | batch·infra 4종 전부가 의존하는 커널(BaseEntity·ErrorCode·seam·testFixtures) |
| `:domain:avoidance` | `:common` | batch 직접 의존 + member·food 의 전이 의존 |
| `:domain:member` | `:common` | `:infra:auth` 가 seam(SocialAccountDeleter)·회원 타입을 의존, food 가 api 노출 |
| `:domain:food` | `:common` | batch 직접 의존(콘텐츠 파이프라인) |
| `:domain:scan` | `:app:api` | api 전용(컨트롤러만 사용) |
| `:domain:bookmark` | `:app:api` | api 전용 |
| `:domain:image` | `:app:api` | api 전용(scan 이 참조 — 같은 api 안) |
| `:domain:metering` | `:app:api` | api 전용(비용 원장 기록은 api 흐름) |
| `:domain:review` | 삭제 | 빈 placeholder — 대응 코드 없음 (`domain/research` 잔존 디렉터리도 함께 정리) |
| `:application` | 분할 | seam 인터페이스·관련 dto(TokenIssuer·TokenParser·SocialTokenVerifier·RefreshTokenStore·PresignedUploadPort) → `:common`, ApplicationService(Home·Auth)·나머지 → `:app:api` |
| `:infra:*` 4종 | 유지 | 의존 선언만 `:application`/`:core`/`:domain:member` → `:common` 으로 재배선 |
| `:app:api`·`:app:batch` | 유지 | 의존 목록이 common+infra 로 축소 |

**Rationale**: common 배치 기준은 "api 밖(배치 또는 인프라 어댑터)이 컴파일 의존하는가" 하나다.
이 기준으로 infra→api 역참조(순환)가 원천 차단된다 — infra 가 참조하던 seam·도메인 타입이 전부 common 에 있게 된다.

**Alternatives considered**: 도메인 8종 전부 common — batch 가 쓰지 않는 scan·bookmark 등까지 공유돼
"batch 가 무엇을 쓰는지"가 모듈 경계에서 다시 안 보이게 됨(다이어트 목적 훼손).

## Decision 3 — 이동 순서: api 전용 흡수 → 공유 추출, 단계마다 그린 빌드

**Decision** (2026-07-28 /speckit-tasks 에서 PR 분할 지시로 개정): **PR #1 = common 분리**(`:common` 신설 →
core·food·member·avoidance·seam 이동·재배선), **PR #2 = api·batch 완성**(api 전용 도메인·application 서비스부
흡수 → 모듈 제거·buildSrc 축소·문서/헌법 갱신). 각 태스크는 전체 빌드 그린을 유지한 채 커밋하고, ArchUnit
도메인 방향 규칙은 도메인의 Gradle 경계가 사라지기 전(PR #1 서두)에 추가한다.

**Rationale**: 문자 그대로 "도메인 전부를 api 로 합친 뒤 꺼내는" 순서는 중간 상태에서 `:infra:auth`(→member)·
`:app:batch`(→food) 가 `:app:api` 를 역참조해야 해 순환으로 컴파일이 깨진다. 목적지가 Decision 2 로 이미
확정돼 있으므로 두 번 옮길 이유도 없다 — 같은 최종 상태를 단계별 그린 빌드로 도달한다.

**Alternatives considered**: 문자 그대로 2-pass 이동 — 중간 상태 빌드 불능 + 전 파일 2회 이동(리뷰 diff 2배).

## Decision 4 — common 소속 패키지는 `com.kbap.common` 계층으로, 나머지 유지 (2026-07-28 개정)

**Decision**: `:common` 으로 이관된 코드는 `com.kbap.common.{core, domain.{food,member,avoidance},
application.{auth,upload}}` 로 패키지를 개편한다(사용자 지시 — 당초 "전부 유지"에서 변경). api 전용
도메인(`com.kbap.domain.{scan,bookmark,image,metering}`)·`com.kbap.application`(서비스부)·`com.kbap.app.*`·
`com.kbap.infra.*` 는 유지. `ModuleBoundaryTest` 는 두 패키지 세계(common.domain / domain)를 모두 다루게
갱신했다 — 커널·도메인 경계·엔티티 위치 규칙은 두 prefix 를 함께 검사하고, 도메인 간 방향 맵은 컨텍스트별
패키지 매핑을 갖는다.

**Rationale**: 소속 모듈이 패키지에 그대로 드러나 공유/전용 구분이 import 문에서 보인다. 배치의
`scanBasePackages`·`@AutoConfigurationPackage("com.kbap")`, api 진입점(`com.kbap` 루트)은 상위 루트가
같아 무수정. 비용: 같은 패키지라 import 없이 참조하던 파일들(seam 소비부)에 명시 import 가 필요해졌다.

**Alternatives considered**: 전부 유지(당초 결정) — 모듈-패키지 불일치가 남는다고 판단해 사용자 지시로 폐기.

## Decision 5 — buildSrc 아키타입 정리

**Decision**: `kbap.domain-conventions` 는 도메인 모듈 소멸로 폐기하되, 그 내용(kotlin-jpa·Boot BOM·
data-jpa api·mysql runtimeOnly·테스트 공통)은 `:common` 의 아키타입(`kbap.common-conventions` 로 개명)으로
승계한다. `kotlin-common`·`spring-conventions`(infra 4종용)·`spring-boot-application`(부트앱 2종용)은 유지.

**Rationale**: 아키타입 소비자가 5→1 로 줄어도 kotlin-jpa(no-arg)·BOM 배선은 컨벤션 플러그인이 이미
검증된 자리다. `:common` build 파일에 인라인하는 안은 core 특수 설정(testFixtures·compileOnly jakarta)과
합쳐지며 오히려 커진다.

**Alternatives considered**: `:common`/build.gradle.kts 에 전부 인라인 — buildSrc 캐시 무효화 트레이드오프는
줄지만 부트앱·인프라 아키타입은 어차피 남아 정리 효과가 없다.

## Decision 6 — 헌법 개정(MAJOR) 동반

**Decision**: 원칙 II("도메인은 컨텍스트별 **모듈**로 둔다")·III(모듈 의존 방향 서술)·IV(경계 강제 수단
서술)의 "모듈" 문언을 "패키지 + ArchUnit" 기준으로 재정의하는 헌법 개정(v5.0.0 → v6.0.0, MAJOR)을 구현과
동시에 반영한다. 원칙 III 의 기존 `:common`(앱 간 공유 계약 — web/jpa/도메인 비의존) 서술은 새 `:common`
(공유 도메인 포함) 정의로 대체한다. 바운디드 컨텍스트·의존 방향·영속 소유의 **취지는 전부 유지**되고
강제 수단만 Gradle 컴파일 → ArchUnit 으로 이동한다.

**Rationale**: KB-134(3.0.0)·KB-220(5.0.0) 선례 — 구조 대개편은 헌법 문언과 함께 움직인다. 개정 없이
구현하면 이후 모든 PR 이 헌법 위반 상태가 된다.

**Alternatives considered**: 헌법은 나중에 — 게이트(모든 설계·PR 은 헌법 준수 검증)가 즉시 깨져 불가.
