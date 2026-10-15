# Quickstart — KB-800

```bash
./gradlew :api:test --tests "com.kbap.api.home.*"
curl -s -H 'X-API-Version: 1.3' 'http://localhost:8080/api/home?lang=en' | jq '.payload.topRatedFoods'
curl -s -H 'X-API-Version: 1.2' 'http://localhost:8080/api/home?lang=en' | jq '.payload | has("topRatedFoods")'   # false
```
