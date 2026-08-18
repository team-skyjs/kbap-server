# Quickstart: KB-338 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.review.*"    # 정렬·필터·커서 시나리오
./gradlew :api:test                                     # api 전체 회귀
```

핵심 시나리오 위치:
- 정렬 5종 순서·동점 규칙 — `GlobalReviewListControllerTest`
- 별점 구간 필터·조합 — `ReviewListControllerTest`
- 동점 경계 커서 페이징(중복·누락 0) — 정렬별 전량 순회 시나리오

## 로컬 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# 평점 높은 순
curl -s "http://localhost:8080/api/reviews?lang=en&sort=RATING_DESC" -H "X-API-Version: 1.0" \
  | jq '[.payload.items[].rating]'

# helpful 내림차순 + 1~3점 필터
curl -s "http://localhost:8080/api/reviews?lang=en&sort=HELPFUL_DESC&minRating=1&maxRating=3" -H "X-API-Version: 1.0" \
  | jq '{ratings: [.payload.items[].rating], likes: [.payload.items[].likeCount], next: .payload.nextCursor}'

# 커서 이어가기 (응답의 nextCursor 를 그대로)
curl -s "http://localhost:8080/api/reviews?lang=en&sort=HELPFUL_DESC&cursor=17_204" -H "X-API-Version: 1.0" | jq '.success'

# 허용값 밖 정렬 → 400
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/reviews?lang=en&sort=RANDOM" -H "X-API-Version: 1.0"
```

## 구현 후 필수 후속

- `kbap-db-review` 스킬로 신규 동적 쿼리(entity join·상관 서브쿼리) 성능 검토 — 인덱스/비정규화 후속 여부 확정.
