# Research: 관리자 음식 목록 음식명 검색

Technical Context 에 NEEDS CLARIFICATION 없음. 설계 선택지만 정리한다.

## R1. 검색 쿼리 방식

- **Decision**: `FoodJpaRepository`에 Spring Data 파생 쿼리
  `findByKoreanNameContaining(koreanName: String, pageable: Pageable): Page<Food>` 추가.
- **Rationale**: 파생 쿼리는 리포지토리 규약(`findBy~`) 안이라 서비스 네이밍 규칙과 충돌하지 않고,
  `Containing` 키워드는 Spring Data 가 LIKE 와일드카드(`%`·`_`)를 이스케이프해 바인딩한다.
  `@SQLRestriction("status = 'ACTIVE'")` 이 소프트 삭제 제외를 자동 적용하므로 별도 조건 불필요.
  수천 건 규모에서 `LIKE '%q%'` 페이지 조회는 충분히 빠르다 — 인덱스·풀텍스트는 YAGNI.
- **Alternatives considered**:
  - 기존 사용자용 `searchFoodPageIds`(custom 구현) 재사용 — READY 상태 필터·커서 기반이라 관리자
    요구(전 상태·페이지 번호 기반)와 맞지 않음. 기각.
  - QueryDSL/Specification — 조건이 "이름 부분 일치" 하나뿐이라 과함. 기각.

## R2. 검색어 파라미터 처리 위치

- **Decision**: 컨트롤러가 `@RequestParam(required = false) q: String?`를 받아 trim 하고,
  `AdminFoodService.getFoodPage(page, query)`가 blank 이면 기존 `findAll`, 값이 있으면 부분 일치 조회로
  분기한다. view 모델(`AdminFoodListPageView`)에 확정된 검색어를 실어 템플릿이 링크에 재사용한다.
- **Rationale**: 공백뿐인 검색어=전체 목록(스펙 Edge Case)을 한 곳에서 처리. 검색어를 view 에 실으면
  템플릿 링크 8곳이 모델 attribute 하나로 일관되게 유지된다.
- **Alternatives considered**: 별도 `searchFoodPage` 메서드 신설 — 페이지네이션·view 조립이 중복된다.
  기존 `getFoodPage` 시그니처 확장(기본값 null)이 호출부 호환도 유지. 기각.

## R3. redirect 검색어 유지 (한글 인코딩)

- **Decision**: `updateFood` POST 는 hidden input 으로 `q`를 받고, redirect URL 은 문자열 보간 대신
  `UriComponentsBuilder`(또는 동등한 인코딩 경로)로 조립해 한글·특수문자 검색어를 URL 인코딩한다.
  `q`가 blank 면 파라미터를 아예 붙이지 않는다.
- **Rationale**: 기존 redirect 는 `"redirect:...page=$safePage..."` 보간인데, `q`는 한글이 기본이라
  인코딩 없이 보간하면 redirect 헤더가 깨질 수 있다. 값이 실리는 파라미터만 인코딩 경로로 바꾼다.
- **Alternatives considered**: `RedirectAttributes` — 기존 컨트롤러가 문자열 뷰명 반환 스타일이라
  이질적. 앵커(`#food-id`) 조합도 UriComponentsBuilder 가 자연스럽다. 기각.

## R4. 병행 브랜치 충돌 주의

- `kb-277-admin-food-soft-delete` 워크트리가 같은 화면(`food-list.html`·`AdminFoodService`)을 만질
  가능성이 있다. 먼저 머지되는 쪽 기준으로 rebase 시 충돌 해소 — 기능상 독립이라 순서 무관.
