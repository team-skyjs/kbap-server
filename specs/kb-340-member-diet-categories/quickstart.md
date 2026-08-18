# Quickstart: 회원 diet 복수 선택 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 신규 회원 토큰 $TOKEN 확보
```

## 1. 온보딩에 diet 포함

```bash
curl -s -X POST localhost:8080/api/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.1" -H "Content-Type: application/json" \
  -d '{"countryCode":"VN","spicinessPreference":"MILD","avoidanceSubstanceCodes":[],"dietCategories":["VEGAN","GLUTEN_FREE"]}'
```

## 2. 조회로 복원 확인

```bash
curl -s localhost:8080/api/members/me/profile -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"
# → payload.dietCategories = ["VEGAN","GLUTEN_FREE"], avoidanceSubstanceCodes 는 기존 의미 그대로
```

## 3. 수정 — 교체·유지·해제

```bash
# 교체
curl -s -X PATCH .../members/me/profile -d '{"dietCategories":["MUSLIM"]}'        # → ["MUSLIM"]
# 누락 = 유지
curl -s -X PATCH .../members/me/profile -d '{"nickname":"새닉"}'                  # → ["MUSLIM"] 유지
# 빈 배열 = 전체 해제
curl -s -X PATCH .../members/me/profile -d '{"dietCategories":[]}'                # → []
```

## 4. 오류·경계

```bash
curl -s -X PATCH .../members/me/profile -d '{"dietCategories":["KETO"]}'          # → 400 MEMBER-011
curl -s -X PATCH .../members/me/profile -d '{"dietCategories":["VEGAN","VEGAN"]}' # → 200, ["VEGAN"] 한 번만
```

## 5. 회귀 확인

- 기존 회원(diet 미저장) 조회 → `dietCategories: []`.
- diet 필드 없는 기존 클라이언트 요청 → 종전과 동일 동작.
- 음식 상세 위험도·스캔 판정 → diet 저장과 무관하게 종전과 동일(직접 지정 재료만).
