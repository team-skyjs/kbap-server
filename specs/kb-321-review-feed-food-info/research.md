# Research: 전체 리뷰 조회(무한 스크롤) 및 리뷰 응답 음식 정보 포함

## R1. 전체 피드 엔드포인트 형태

- **Decision**: 신규 경로 `GET /api/v1/reviews/feed` 를 추가한다. 기존 `GET /api/v1/reviews`(foodId 필수)는 그대로 둔다.
- **Rationale**: 기존 경로의 `foodId` 를 optional 로 완화하면 한 엔드포인트가 두 의미(음식별/전체)를 가져 계약이 모호해지고, `countryCode` 필터처럼 음식별에만 있는 파라미터의 적용 여부도 갈린다. 별도 경로가 문서·클라이언트 분기 모두 명확하다.
- **Alternatives considered**: `GET /reviews` 의 foodId optional 화(기존 계약 변경·의미 중첩으로 기각), `GET /feed`(리뷰 리소스 소속이 드러나지 않아 기각).

## R2. 음식 이름의 언어 처리 — `lang` 필수 추가

- **Decision**: 세 목록 경로(피드·음식별·내 리뷰) 요청에 `lang` 을 **필수**(`@field:NotBlank`)로 추가하고, `LanguageCode.from(lang)`(미지원 코드 → EN 폴백) + `Food.displayName(lang)`(번역 부재 → ko 폴백)으로 이름을 해석한다.
- **Rationale**: 음식 이름은 ko 원문 + 9개 언어 사전 번역 구조라 표시 언어 없이 이름을 정할 수 없다. 헌법 V 가 "표시 언어를 받는 API 는 lang 필수·기본값 금지"를 강제하며, `FoodController`(KB-201)가 동일 패턴의 선례다.
- **Alternatives considered**: lang optional + ko 기본(헌법 V 위반으로 기각), koreanName 만 반환(외국인 대상 서비스 가치 훼손으로 기각).
- **감수하는 비용**: 기존 목록 2개에 필수 파라미터가 추가되므로 lang 을 안 보내는 구버전 클라이언트는 400 을 받는다. 클라이언트는 어차피 음식 정보 렌더링을 위해 함께 업데이트되므로 앱 릴리즈와 동기 배포한다(스키마 리비전 공존 이슈 없음 — DB 무변경).

## R3. 응답의 음식 정보 형태

- **Decision**: `ReviewResponse` 에 중첩 객체 `food: ReviewFoodResponse?`(`foodId`·`name`·`imageUrl`)를 추가한다. 기존 top-level `foodId` 는 유지한다(하위 호환). 목록 조회에서만 채우고, 리뷰 생성·수정 응답에서는 `null` 로 둔다.
- **Rationale**: 작성자(`author`) 중첩 객체 선례와 대칭. 생성·수정은 음식 상세 화면 맥락에서 호출되어 클라이언트가 이미 음식 정보를 갖고 있고, 요청에 lang 을 추가로 강제할 이유가 없다.
- **Alternatives considered**: top-level 평탄 필드 `foodName`·`foodImageUrl`(author 와 비대칭·확장성 낮음으로 기각), 생성·수정 응답까지 채움(create/update 요청에 lang 필수화가 필요해져 기각).

## R4. 삭제된 음식의 리뷰 제외 (피드)

- **Decision**: `findGlobalReviewPage` JPQL 에 `exists (select 1 from Food f where f.id = r.foodId)` 서브쿼리를 넣는다. `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 이 HQL 의 Food 참조에도 적용되어 소프트 삭제 음식이 자동 제외된다.
- **Rationale**: 애플리케이션에서 후필터링하면 페이지 크기가 20 미만으로 줄거나 재조회 루프가 필요하다. DB 에서 제외해야 커서 페이지네이션이 일관된다. Food 참조는 JPQL 문자열이라 `common.domain.review` → `food` 컴파일 의존이 생기지 않는다.
- **Alternatives considered**: 조회 후 앱 필터(페이지 축소 문제로 기각), Review 에 food 상태 스냅샷 컬럼 추가(스키마 변경·정합 유지 비용으로 기각).
- **검증**: `@SQLRestriction` 의 서브쿼리 적용 여부는 repository 테스트(삭제 음식 리뷰 제외)가 Red 단계에서 실증한다. 미적용으로 판명되면 `and f.status = com.kbap.common.domain.EntityStatus.ACTIVE` 명시 조건으로 대체(테스트는 그대로 유효).
- **음식별·내 리뷰 경로**: 기존 쿼리는 손대지 않는다. 음식이 삭제된 리뷰의 `food` 는 배치 조회에서 빠져 `null` 로 내려간다(내 리뷰 화면에서 리뷰 자체는 보임 — 기존 노출 규칙 유지).

## R5. 차단·신고 필터 재사용

- **Decision**: 피드 쿼리에 음식별 조회와 동일한 `excludedMemberIds`(차단 회원, `MemberBlockService`) + `excludedReviewIds`(내가 신고한 리뷰, `ReportJpaRepository`) 조건을 적용한다. 빈 목록 → `-1L` 센티널 규칙도 그대로.
- **Rationale**: KB-294 가 확립한 규칙 — 전체 피드라고 차단·신고 노출 규칙이 달라질 이유가 없다.
- **Alternatives considered**: 없음(기존 규칙 준수).

## R6. 커서·페이지 크기·N+1

- **Decision**: 기존 규칙 재사용 — `r.id < :cursor` + `order by r.id desc`, `PAGE_SIZE(20)+1` 조회로 `hasNext` 판정, `nextCursor = 마지막 id`. 음식 정보는 `FoodJpaRepository.findAllById(foodIds)` 배치 조회 1회로 합류(작성자·좋아요 배치 조회와 동일 패턴).
- **Rationale**: id 는 단조 증가라 페이지 진행 중 신규 리뷰가 끼어도 중복·누락이 없다(스펙 엣지 케이스 충족). 페이지당 쿼리 수가 리뷰 건수와 무관하게 고정된다.
- **Alternatives considered**: createdAt 커서(동시각 중복 처리 복잡, id 와 등가로 기각).
