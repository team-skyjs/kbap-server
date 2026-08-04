# Implementation Plan: 커뮤니티 피드 조회 + 글 상세 — 커서 페이징·게스트 게이트

**Branch**: `kb-291-community-feed-read` | **Date**: 2026-08-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-291-community-feed-read/spec.md`

## Summary

KB-290 게시글 도메인 위에 읽기 API 두 개를 얹는다: 최신순 커서 페이징 피드(`GET /api/v1/community/posts`)와 글 상세(`GET /api/v1/community/posts/{postId}`). 둘 다 게스트 접근 가능하되 피드는 2페이지(40건) 게이트 — 초과 커서는 `COMMUNITY-005`(401) 로 로그인 유도. 탈퇴 작성자의 글은 조회에서 숨긴다(member 소프트 삭제 + exists 서브쿼리가 판정을 공짜로 준다). 스키마 변경 없음, 신규 쿼리 2개·에러 코드 1개·필터 GET 예외 1건. 조회·조립 경로를 `CommunityService` 한 곳에 모아 후속 차단·신고·번역 태스크의 단일 수정 지점을 만든다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi. 신규 의존성 없음

**Storage**: MySQL — 기존 `community_post` 테이블 그대로, 마이그레이션 없음

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`/MockMvc + MySQL Testcontainers

**Target Platform**: `:api` web bootJar (batch·infra 무관)

**Project Type**: 모듈러 모놀리스 백엔드 — 이번 변경은 `:api` + `:common` 만

**Performance Goals**: 피드 페이지당 DB 조회 고정 3회(피드 1·회원 1·음식 1) — 항목 수 비례 금지(SC-005)

**Constraints**: 기존 커서 규약(`Page<T>`/`CursorParser`/PAGE_SIZE 20) 유지, KB-290 쓰기 API 경로·계약 불변, 격리수준 조정 금지

**Scale/Scope**: API 2개, 응답 DTO 3개, 리포지토리 쿼리 2개, 에러 코드 1개, 필터 예외 1건, 통합 테스트 1클래스

## Constitution Check

*GATE: Phase 0 전 통과 확인, Phase 1 설계 후 재확인 — v7.0.0 기준.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 통합 테스트(피드·게이트·상세·익명화) 선작성 → Red 확인 → 구현. tasks 단계에서 Red→Green 순서 강제 |
| II. Bounded Contexts | PASS | 조회 조합·응답 조립은 `com.kbap.api.community`(도메인 맵 대상 아님). `common.domain.community` 에는 리포지토리 쿼리만 추가 — 타 도메인 의존 신설 없음(member·food 접근은 api 계층에서) |
| III. Layered Dependency Direction | PASS | api → common 방향만. infra·batch·seam 변경 없음 |
| IV. Persistence Ownership | PASS | 새 엔티티·연관관계 없음. 쿼리는 소유 패키지(`common.domain.community`)의 public 리포지토리에. 조회 트랜잭션은 `@Transactional(readOnly = true)` 명시. 창구 서비스 신설 없음(기존 `CommunityService` 에 조회 메서드 추가) |
| V. Language Policy | PASS | `lang` 필수(@NotBlank, 컨트롤러 DTO 소유) → `LanguageCode.from`. 음식명은 `displayName(lang)` 기존 3분기(빈 값 400/부재 ko/미지원 en) 그대로. "탈퇴한 사용자" 는 UI 문구 — 콘텐츠 번역 정책과 분리 유지(R8) |

**Post-Phase 1 재확인**: PASS — 설계 산출물(data-model·contracts)이 위 판정을 바꾸지 않는다. `JwtAuthenticationFilter` GET 예외는 api.core 내부 변경으로 경계 원칙과 무관하며, 게스트 판정 로직은 서비스가 소유한다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-291-community-feed-read/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 11건(R1~R11)
├── data-model.md        # Phase 1 — 읽기 표현·신규 쿼리(스키마 변경 없음)
├── quickstart.md        # Phase 1 — 검증 명령·구현 파일 지도
├── contracts/
│   └── feed-api.md      # Phase 1 — GET 2개 API 계약·에러 코드·필터 예외
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── core/error/ErrorCode.kt                      # [수정] COMMUNITY_LOGIN_REQUIRED(COMMUNITY-005, 401)
└── domain/community/PostingJpaRepository.kt     # [수정] findPage(@Query keyset) · findIdsFrom(게이트용 LIMIT 프로젝션)

api/src/main/kotlin/com/kbap/api/
├── community/
│   ├── CommunityService.kt                      # [수정] getPostingPage·getPosting + 단일 조립 함수(R10)
│   ├── CommunityController.kt                   # [수정] GET 2개(@AuthMemberIdOrNull)
│   ├── CommunityApi.kt                          # [수정] swagger 문서
│   ├── PostingListRequest.kt                  # [신규] lang 필수·cursor 선택
│   └── PostingItemResponse.kt             # [신규] + PostingAuthorResponse·PostingFoodTagResponse
└── core/
    ├── auth/JwtAuthenticationFilter.kt          # [수정] shouldNotFilter — GET 게스트 예외(R5)
    └── config/WebConfig.kt                      # [수정] 예외 패턴 2건 주입

api/src/test/kotlin/com/kbap/api/
└── community/PostingReadControllerTest.kt     # [신규] 피드·게이트·상세·익명화·태그 통합 테스트
```

**Structure Decision**: 기존 `com.kbap.api.community` 기능 패키지에 읽기 유스케이스를 합류시킨다(ADR-0017 — 기능 패키지에 controller·DTO·서비스 동거). 영속 쿼리만 소유 도메인 패키지(`common.domain.community`)에 추가한다. 신규 모듈·패키지·마이그레이션 없음.

## 설계 핵심 (research.md 요약)

1. **커서 게이트(R2)**: 게스트 + 커서 존재 시 `findIdsFrom(cursor, LIMIT 21).size > PAGE_SIZE` 면 `COMMUNITY-005`. 커서 위치 기반이라 임의 커서 우회 불가, 상태 추적 불필요. LIMIT 프로젝션이라 악의적 깊은 커서에도 스캔 최대 21행.
2. **필터 예외(R5, 사용자 확정)**: 같은 리소스 같은 경로 원칙. `shouldNotFilter` 는 GET + `^/api/v1/community/posts$`·`^/api/v1/community/posts/\d+$` 정확 일치만 — 후속 댓글 GET 은 계속 보호된다.
3. **만료 토큰(R4)**: 조용한 게스트 강등 금지 — `@AuthMemberIdOrNull` 기존 동작(만료 → AUTH-004) 유지로 회원이 게이트에 오인 차단되는 일을 막는다.
4. **탈퇴 작성자 글 숨김(R8, 개정 2026-08-04)**: 피드·게이트 쿼리 `exists(Member)` + 상세 `existsById` 검증 — 탈퇴 작성자 글은 존재 자체를 숨긴다(COMMUNITY-001). 댓글의 "(삭제)" 표기는 KB-292 몫.
5. **음식 태그(R6, 사용자 확정)**: `{foodId, name}` — `getReadyFoodsByIds` 일괄 조회 + `displayName(lang)`. 미존재 id 는 태그만 탈락(글은 정상).
6. **카운트(R9)**: like/dislike/comment 0 고정 — 계약 자리만 확정.

## Complexity Tracking

위반 없음 — 표 생략.
