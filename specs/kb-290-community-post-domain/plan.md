# Implementation Plan: 커뮤니티 게시글 도메인 — 작성/수정/삭제

**Branch**: `kb-290-community-post-domain` | **Date**: 2026-08-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-290-community-post-domain/spec.md`

## Summary

커뮤니티 게시판의 토대인 게시글 도메인을 신설한다 — `community` 컨텍스트(`community_post` 단일 테이블·엔티티·리포지토리)와 작성/수정/삭제 API 3본. 리뷰 도메인 선례를 최대 재사용한다: 사진은 JSON `image_refs`(≤4, 첫 장 = 커버) + 기존 presigned 업로드·소유 검증, 음식 태그도 JSON `food_ids`(READY 음식만, 글당 ≤3·중복 불가 — 서비스 검증), 유스케이스 서비스는 `com.kbap.api.community` 기능 패키지. 장소 태그는 스코프 제외. 삭제는 BaseEntity 소프트 삭제로, 후속 댓글 통삭제 정책이 조회 경로 설계만으로 성립하는 모델을 만든다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, 기존 이미지 업로드 인프라(`com.kbap.api.image` + `common.domain.image`)

**Storage**: MySQL (Flyway 마이그레이션, 스키마 owner = `:api`) — 신규 테이블 `community_post` 하나(이미지·음식 태그는 JSON 컬럼, 명시 인덱스 없음 — 실측 후 추가)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + JUnit 5 플랫폼, 통합 테스트 MySQL Testcontainers(`@SpringBootTest` + MockMvc), ArchUnit(`ModuleBoundaryTest`)

**Target Platform**: Linux 서버 (web bootJar `:api`)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경은 `:common`(엔티티·리포) + `:api`(기능 패키지·마이그레이션)만

**Performance Goals**: 단건 CRUD — 특별 목표 없음(표준 웹 응답). 피드 조회 성능·인덱스는 KB-291 에서 실측 후 판단

**Constraints**: 본문 ≤2,000자·사진 ≤4장·음식 태그 ≤3개(중복 불가·READY 만), 회원 전용·본인만 수정/삭제, 소프트 삭제, JPA 연관관계 금지(id 참조), FK 는 Flyway 강제

**Scale/Scope**: API 3본(작성/수정/삭제), 엔티티 1종, 마이그레이션 1건, 에러 코드 4종(COMMUNITY-001~004), `UploadPurpose.COMMUNITY` 추가

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | tasks 단계에서 엔티티 검증·서비스·MockMvc 통합 테스트를 Red 우선 작성. 테스트 스택 기존 그대로 |
| II. Bounded Contexts | PASS | 신규 컨텍스트 `community` 를 `common.domain.community` 로 신설, 타 도메인 참조는 Long id 값만(엔티티 import 없음) → `ModuleBoundaryTest` 허용 맵에 `"community" to emptySet()` 추가. 교차 도메인 조합(FoodService·이미지 검증)은 `com.kbap.api.community` 소유 |
| III. Layered Dependency Direction | PASS | `:api` → `:common` 방향만. 신규 seam 없음(외부 시스템 미사용). 구 계층 패키지 미사용 |
| IV. Persistence Ownership | PASS | 엔티티·리포 public, 엔티티 = 도메인 모델(검증·update·isOwnedBy 내장), JPA 연관관계 없음, FK(member_id)는 Flyway, 서비스 public 메서드 명시 `@Transactional` |
| V. Language Policy | PASS(해당 없음) | 커뮤니티 UGC 는 음식 콘텐츠 사전 번역 정책 대상이 아님(번역은 KB-295 온디맨드). `lang` 파라미터 없는 API |

**Post-design re-check**: PASS — Phase 1 산출물이 위 판정을 바꾸지 않음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-290-community-post-domain/
├── plan.md              # 이 문서
├── research.md          # R1~R8 결정(이미지 JSON·태그 FK 테이블·edited_at·소프트삭제·배치·에러코드)
├── data-model.md        # 엔티티·마이그레이션 DDL·상태 전이
├── quickstart.md        # 수동 검증 시나리오
├── contracts/
│   └── community-post-api.md   # POST/PUT/DELETE /api/v1/community/posts
└── tasks.md             # /speckit-tasks 산출(이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/community/
├── model/
│   └── Posting.kt                    # 엔티티=도메인 모델 (content·imageRefs·foodIds·editedAt·update·isOwnedBy) — 테이블 community_post
└── PostingJpaRepository.kt

common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt   # COMMUNITY-001~004 추가

api/src/main/kotlin/com/kbap/api/community/
├── CommunityApi.kt                   # swagger 문서 인터페이스
├── CommunityController.kt            # POST/PUT/DELETE, @AuthMemberId
├── CommunityService.kt               # 유스케이스 조합 (@Transactional, 음식 READY·이미지 소유 검증)
├── CommunityCreateRequest.kt         # content·imagePaths·foodIds (Bean Validation)
└── CommunityPostingResponse.kt

api/src/main/kotlin/com/kbap/api/image/UploadPurpose.kt          # COMMUNITY("community") 추가

api/src/main/resources/db/migration/
└── V<timestamp>__community_post_table.sql                       # community_post (단일 테이블)

api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt  # "community" to emptySet()

# 테스트 (소스 미러링)
common/src/test/kotlin/com/kbap/common/domain/community/model/PostingTest.kt
api/src/test/kotlin/com/kbap/api/community/CommunityControllerTest.kt   # MockMvc 통합
```

**Structure Decision**: 리뷰 도메인과 동일 배치 — 영속은 `:common`(`common.domain.community`), api 전용 유스케이스·DTO·컨트롤러는 `com.kbap.api.community` 기능 패키지, 마이그레이션은 `:api`(스키마 owner). 신규 모듈·신규 seam 없음.

## Complexity Tracking

위반 없음 — 기존 아키타입·패턴 재사용만으로 구성.
