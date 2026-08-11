# Data Model: 전체 리뷰 조회(무한 스크롤) 및 리뷰 응답 음식 정보 포함

## 스키마 변경

**없음.** 신규 테이블·컬럼·인덱스·Flyway 마이그레이션이 없다. 기존 `review`·`food` 테이블 조회만 추가한다.

> 피드 쿼리는 `review.id desc`(PK 역순) 스캔 + `food` 존재 서브쿼리라 기존 PK·`review.food_id` FK 인덱스로 충분하다. 성능 이슈가 실측되면 그때 인덱스를 검토한다.

## 엔티티 (변경 없음 — 참조용)

- **Review** (`common.domain.review.model.Review`): `memberId: Long`, `foodId: Long`, `rating`, `content?`, `imageRefs?`, `authorCountryCode?`. BaseEntity 상속(id·status·createdAt) — 소프트 삭제 자동 필터.
- **Food** (`common.domain.food.model.Food`): `displayName(lang: LanguageCode): String`(번역 부재 → ko 폴백), `imageRef: String?`. 소프트 삭제 시 `@SQLRestriction` 으로 조회에서 제외.
- **Member / ReviewLike / Report / MemberBlock**: 기존 그대로 — 작성자·좋아요·신고 제외·차단 제외 배치 조회에 사용.

## 쿼리 추가 (`ReviewJpaRepository`)

```
findGlobalReviewPage(cursor: Long?, excludedMemberIds: List<Long>, excludedReviewIds: List<Long>, pageable): List<Review>
```

- 조건: `(:cursor is null or r.id < :cursor)` + `r.memberId not in :excludedMemberIds` + `r.id not in :excludedReviewIds` + `exists (select 1 from Food f where f.id = r.foodId)`(삭제 음식 제외)
- 정렬: `order by r.id desc`, 조회 크기 `PAGE_SIZE + 1`(hasNext 판정)

## API 응답 DTO 변경 (`com.kbap.api.review`)

```
ReviewResponse                      # 기존 필드 전부 유지, food 추가
├── (기존) reviewId·foodId·memberId·rating·content·imageUrls·createdAt·author·likeCount·likedByMe
└── food: ReviewFoodResponse?       # 신규 — 목록 조회에서 채움, 생성·수정 응답은 null

ReviewFoodResponse                  # 신규
├── foodId: Long
├── name: String                    # Food.displayName(lang) 해석 결과
└── imageUrl: String?               # ImageUrls.resolve(publicBaseUrl, food.imageRef)
```

- `food = null` 인 경우: 생성·수정 응답, 그리고 (음식별·내 리뷰 경로에서) 음식이 소프트 삭제된 리뷰.

## 요청 DTO 변경

- `ReviewListRequest`·`MyReviewListRequest`: `lang: String` 필수(`@field:NotBlank`) 추가.
- `FeedReviewListRequest`(신규): `lang: String` 필수 + `cursor: String?`.

## 상태 전이

없음 — 조회 전용 기능.
