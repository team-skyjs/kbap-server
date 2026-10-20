# Quickstart: KB-802 BE 실측

```bash
docker compose up -d mysql redis
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

```bash
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews/{id}?lang=en"                       # 게스트 200, likedByMe=false, food.koreanName
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews/{id}?lang=ko"                       # food.koreanName = null
curl -s -H "X-API-Version: 1.0" -H "Authorization: Bearer $T" "localhost:8080/api/reviews/{id}?lang=en"  # 회원 likedByMe
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews/999999999?lang=en"                  # 400 REVIEW-001
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews/{id}"                               # 400 COMMON-002
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews/abc?lang=en"                        # 400 COMMON-002
curl -s -H "X-API-Version: 1.0" -o /dev/null -w '%{http_code}' "localhost:8080/api/reviews/me?lang=en"  # 401
curl -s -H "X-API-Version: 1.0" "localhost:8080/api/reviews?lang=en" | jq '.payload.items[0].food'      # koreanName 포함
```
