# Research: 리뷰 목록 정렬·필터 (KB-338)

## Decision 1: 커서 전략 — (지표, id) 복합 keyset, LATEST 는 기존 형식 유지

- **Decision**: 모든 정렬을 (지표, id desc) 복합 정렬로 고정하고 커서에 마지막 행의 지표값과 id 를 함께 담는다(`"{metric}_{id}"`). LATEST 는 기존 숫자 커서 그대로.
- **Rationale**: keyset 만이 "중복·누락 없음"(FR-005)을 보장한다 — offset 은 페이징 중 삽입·삭제에 흔들린다. id 2차 키가 동점을 결정적으로 끊는다(FR-002). LATEST 형식 유지로 기존 클라이언트 무영향(SC-004).
- **Alternatives considered**: offset 페이징(중복·누락 발생 — 기각), 커서에 정렬 종별 태그 포함(교차 사용까지 400 으로 잡을 수 있으나 형식 복잡 — 스펙이 "정의된 규칙" 허용이라 과설계로 기각), Base64 불투명화(디버깅만 어려워짐 — 기각).

## Decision 2: 응답 커서 타입 — 리뷰 목록 전용 `ReviewListPage(nextCursor: String?)`

- **Decision**: 공용 `Page<T>`(nextCursor: Long)는 손대지 않고 리뷰 목록 응답만 전용 봉투로 바꾼다.
- **Rationale**: 복합 커서는 Long 에 안 담긴다. 공용 Page 를 String 으로 바꾸면 food·bookmark·community·me 까지 number→string 계약 변경이 번진다 — 영향 최소화.
- **Alternatives considered**: 공용 Page nextCursor String 화(전 목록 API 계약 변경 — 기각), 지표를 Long 하나에 비트팩(범위 제약·해독 불가 — 기각).
- **트레이드오프**: 리뷰 목록의 `nextCursor` 가 number→string 으로 바뀐다 — 클라이언트 조율 필요(무버전 즉시 변경, 팀 관행).

## Decision 3: 쿼리 구현 — custom repository 동적 JPQL (QueryDSL 미도입)

- **Decision**: `ReviewRepositoryCustom(Impl)` 에서 EntityManager 로 JPQL 문자열을 분기 조립한다(정렬·커서·필터). `FoodRepositoryCustomImpl` 선례 패턴.
- **Rationale**: 정렬 5종 × 커서 조건 × 필터 조합을 고정 `@Query` 로 두면 메서드가 폭발한다. QueryDSL 은 신규 의존 + 컨벤션 부재 — 한 조회를 위해 도입할 근거 부족.
- **Alternatives considered**: 고정 @Query 5벌(커서 유무까지 10벌 — 기각), QueryDSL(신규 의존 — 기각), Specification API(정렬·having 표현이 오히려 난해 — 기각).

## Decision 4: helpful 집계 — HQL entity join + group by, 응답 조립은 불변

- **Decision**: 정렬용 좋아요 수는 `left join ReviewLike l on l.reviewId = r.id` + `count(l)` 로 쿼리 안에서 계산한다. 응답의 `likeCount` 는 기존처럼 페이지 20건 대상 별도 집계 유지.
- **Rationale**: Hibernate 6 HQL 이 무연관 entity join 을 지원하고 `@SQLRestriction` 이 join 대상에도 적용된다(활성 좋아요만 — 위키 실증 선례). 응답 조립까지 join 결과로 갈아타면 `toResponses` 공용 경로(recentReviews 포함)가 흔들린다 — 20건 재집계 비용은 무시 가능.
- **Alternatives considered**: like_count 비정규화 컬럼(스키마 변경 + 정합 유지 부담 — 실측 전 과설계), 상관 서브쿼리로 계산(집계 join 보다 행당 반복 — group by 로 통일).

## Decision 5: 음식 리뷰 수 — 상관 서브쿼리, 스냅샷 없음

- **Decision**: `(select count(r2) from Review r2 where r2.foodId = r.foodId)` 상관 서브쿼리로 정렬·커서 조건에 쓴다.
- **Rationale**: 활성 리뷰만 세는 것이 `@SQLRestriction` 으로 자동 보장. food 테이블에 집계 컬럼을 두는 비정규화는 실측 전 과설계.

## Decision 6: 검증·에러 코드 — 신규 채번 없음

- **Decision**: sort 허용값 밖·별점 구간 오류(범위 밖·min>max)는 400 `COMMON-002`, 커서 형식 오류는 400 `FOOD-002`(INVALID_CURSOR — 기존 커서 오류와 동일 계열).
- **Rationale**: 클라이언트 분기 요구가 "400 거절" 뿐이라 신규 코드가 줄 정보가 없다.

## 코드 조사 결과

- `Review.rating` — `TINYINT` Int 컬럼, 1~5 검증은 엔티티 init 에 존재. 필터 검증은 요청 경계(DTO) 소유(헌법 V).
- `CursorParser` — 숫자 전용(음수·비숫자 → FOOD-002). 복합 커서 파싱은 api.review 의 신규 코덱이 담당하고, LATEST 경로는 CursorParser 재사용.
- `findReviewPage` 호출 2곳: `getReviewPage`(이번에 확장)·`getRecentFoodReviews`(LATEST 고정 — 새 계약의 기본 경로 재사용, 쿼리 이원화 방지).
- `Page<T>` 소비처: food·bookmark·community·review — 공용 봉투 변경은 파급이 커서 기각(Decision 2).
