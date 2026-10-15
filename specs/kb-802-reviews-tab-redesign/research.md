# Research: KB-802 BE

## R1. lang 을 요청 객체로 받는 이유
- Decision: `@Valid @ModelAttribute ReviewDetailRequest(@field:NotBlank lang)`.
- Rationale: `GlobalExceptionHandler` 에 `MissingServletRequestParameterException` 핸들러가 없어 `@RequestParam` 누락이 500 이 된다. 목록과 같은 방식이면 누락 = 400 COMMON-002.
- Alternatives: `@RequestParam` + 핸들러 추가 — 전역 동작을 바꾸므로 기각.

## R2. 가시성 판정
- Decision: `ReviewService.getVisibleReview` 재사용.
- Rationale: soft delete 는 `@SQLRestriction` 으로 `findById` 에서 빠지고, 차단 작성자·음식 부재는 `existsFeedVisible`, 본인은 우회. 번역 API 와 같은 판정이라 규칙이 한 곳에 있다.

## R3. 게스트 허용
- Decision: `GuestExemption("GET", Regex("^/api/reviews/\\d+$"))`.
- Rationale: `\d+` 라 `/reviews/me` 는 잡히지 않는다. 토큰이 있으면 `AuthMemberIdOrNull` 이 해석(목록과 동일).

## R4. koreanName 규칙
- Decision: `food.displayName(KO).takeIf { it != name }`.
- Rationale: `FoodSummaryView`·`FoodService` 와 같은 식. lang=ko 또는 번역 부재로 한국어 폴백이면 null.

## R5. HTTP 상태
- Decision: REVIEW-001 = 400 유지(cto A-1). PATCH·DELETE·like·번역 응답을 바꾸지 않기 위함.
