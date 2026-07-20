# Quickstart: lang 파라미터 정책 통일 검증

## 자동 검증

```bash
./gradlew :core:test --tests "com.kbap.core.lang.LanguageCodeTest"
./gradlew :app:api:test --tests "com.kbap.app.api.home.*"
./gradlew :app:api:test --tests "com.kbap.app.api.food.*"
./gradlew build          # 전체 회귀
```

기대 커버리지:

| 케이스 | 위치 |
|---|---|
| `from` — 지원 10종 정확 일치 | `core` `LanguageCodeTest` |
| `from` — 미지원·대소문자·지역 변형·**앞뒤 공백** → EN | `core` `LanguageCodeTest` |
| `from` 이 더 이상 예외를 던지지 않음 | `core` `LanguageCodeTest` (기존 `shouldThrow` 5개 전환) |
| `lang` 누락·빈 값·공백 → 400 `COMMON-002` | 5개 엔드포인트 각 테스트 |
| `lang=ja` → 일본어 (회원·비회원 동일) | `HomeGuestTest`·`HomeControllerTest` |
| `lang=fr` → 200 + 영어 | `FoodSearchControllerTest`(기존 400 케이스 전환) |
| 회원(프로필 `ja`) + `lang=ko` → **한국어** | `HomeControllerTest` |
| 같은 `lang` 이면 5개 API 응답 언어 일치 | 교차 검증 케이스 |

`ErrorCode.UNSUPPORTED_LANGUAGE` 참조가 남아 있으면 컴파일이 깨진다 — 이것이 누락 탐지 장치다.

## 수동 검증

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

```bash
# 누락·빈 값·공백 → 전부 400 COMMON-002 (5개 API 동일)
for p in /home /foods /foods/search /bookmarks; do
  curl -s "http://localhost:8080/api/v1$p" | jq -c '{p:"'$p'", success, code}'
done
curl -s 'http://localhost:8080/api/v1/foods?lang='    | jq '{success, code}'
curl -s 'http://localhost:8080/api/v1/foods?lang=%20' | jq '{success, code}'

# 지원 언어 반영
curl -s 'http://localhost:8080/api/v1/foods?lang=ja' | jq '.payload.items[0].name'
curl -s 'http://localhost:8080/api/v1/home?lang=ja'  | jq '.payload.popularFoods[0].name'

# 미지원 → 400 이 아니라 영어 (전에는 400 COMMON-001)
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8080/api/v1/foods?lang=fr'   # 200
curl -s 'http://localhost:8080/api/v1/foods?lang=fr'      | jq '.payload.items[0].name'
curl -s 'http://localhost:8080/api/v1/foods?lang=JA'      | jq '.payload.items[0].name'
curl -s 'http://localhost:8080/api/v1/foods?lang=%20ko%20' | jq '.payload.items[0].name'  # 공백 → 영어

# 홈: 프로필 언어가 ja 인 회원 — lang 이 이긴다
curl -s -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/home?lang=ko' \
  | jq '.payload.popularFoods[0].name'      # 한국어

# 교차 일관성 — 같은 lang 이면 홈과 목록의 표시 언어가 같다
curl -s 'http://localhost:8080/api/v1/home?lang=ja'  | jq '.payload.popularFoods[0].name'
curl -s 'http://localhost:8080/api/v1/foods?lang=ja' | jq '.payload.items[0].name'
```

## Swagger 확인

`http://localhost:8080/swagger-ui.html` — 5개 엔드포인트 전부에서:

- `lang` 이 **required 쿼리 파라미터**로 표시된다 — 요청 DTO 가 펼쳐지지 않으면 `@ParameterObject` 를 붙인다
- 설명에 지원 10종 목록·미지원 값의 영어 폴백이 있다
- 400 응답이 "`lang` 누락·빈 값" 사유로 문서화돼 있다 (**값이 지원 목록에 없어서가 아님**)
- `COMMON-001` 언급이 남아 있지 않다

## 거버넌스 산출물 확인

```bash
grep -n "fail-fast\|400" .specify/memory/constitution.md | grep -i lang   # 원칙 V 개정 반영
ls docs/adr/0013-*                                                        # ADR 신규
grep -rn "superseded" specs/008-unsupported-language-error/                # supersede 표기
```
