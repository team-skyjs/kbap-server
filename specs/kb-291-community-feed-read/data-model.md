# Data Model: 커뮤니티 피드 조회 + 글 상세 (KB-291)

**스키마 변경 없음.** KB-290 의 `community_post` 테이블·`Posting` 엔티티를 그대로 읽는다. 이 문서는 읽기 표현(조립 모델)과 신규 쿼리만 정의한다.

## 기존 엔티티 (변경 없음)

### Posting (`community_post`)

| 필드 | 타입 | 비고 |
|------|------|------|
| id | Long (PK) | 커서 기준값(최신순 = id 내림차순) |
| memberId | Long | 작성자 — 익명화 판정에 사용 |
| content | String(2000) | |
| imageRefs | JSON List<String>? | 스토리지 키 — 공개 URL 로 변환해 응답 |
| foodIds | JSON List<Long>? | 음식 태그 — 이름 조인해 응답 |
| editedAt | LocalDateTime? | 응답 미포함(수정 표시 없음 정책) |
| status / createdAt / updatedAt | BaseEntity | `@SQLRestriction` 이 DELETED 자동 제외 |

## 신규 리포지토리 쿼리 (`PostingJpaRepository`)

| 메서드 | 형태 | 용도 |
|--------|------|------|
| `findPage(cursor, pageable)` | `@Query` — `cursor null 이면 전체, 아니면 id < :cursor` + `exists(Member)`, id DESC | 피드 페이지(PAGE_SIZE+1 건) — 탈퇴 작성자 글 제외(R8) |

게스트 게이트(R2)는 커서 존재 여부만 보는 서비스 로직이라 별도 쿼리가 없다.

둘 다 `@SQLRestriction` 으로 ACTIVE 만 본다 — status 조건을 손으로 달지 않는다(컨벤션).

## 읽기 표현 (api — `com.kbap.api.community`)

### PostingItemResponse — 피드 항목이자 상세 응답 (동일 형태)

| 필드 | 타입 | 소스 |
|------|------|------|
| postId | Long | Posting.id |
| author | PostingAuthorResponse | member 일괄 조회 + 익명화 규칙 |
| content | String | Posting.content |
| imageUrls | List<String> | imageRefs → ImageUrls.resolve (첫 장이 커버) |
| foodTags | List<PostingFoodTagResponse> | foodIds → Food.displayName(lang), 미존재 id 제외 |
| likeCount / dislikeCount / commentCount | Int | 상수 0 (R9) |
| createdAt | LocalDateTime | Posting.createdAt |

### PostingAuthorResponse — 노출되는 글의 작성자는 항상 활성 회원(탈퇴 글은 조회 제외)

| 필드 | 값 |
|------|----|
| memberId | Long (non-null) |
| nickname | profile.nickname (미설정 시 null) |
| profileImageUrl | ImageUrls.resolve(profile.profileImageUrl) — 없으면 null(FE 기본 아바타) |

### PostingFoodTagResponse

| 필드 | 타입 |
|------|------|
| foodId | Long |
| name | String — 요청 lang 기준(부재 시 ko 폴백) |

### 페이지 봉투

`api.core.Page<PostingItemResponse>` 재사용 — `items` / `hasNext` / `nextCursor`.

## 상태·전이

새 상태 없음. 탈퇴 작성자 글 숨김은 저장 상태가 아니라 **조회 시점 판정**(member ACTIVE 존재 여부)이다.

## 조립 흐름 (단일 경로 — FR-010)

```
피드: findPage(cursor, 21) ─┐
상세: findById(postId)        ─┴→ assemble(postings, lang):
                                    1. memberRepository.findAllById(작성자 id 집합)
                                    2. foodService.getReadyFoodsByIds(태그 id 합집합)
                                    3. 항목별 매핑(태그 이름·URL 변환·카운트 0)
```

쿼리 횟수는 페이지당 고정(피드 1 + 회원 1 + 음식 1)— 항목 수 비례 아님(SC-005).
