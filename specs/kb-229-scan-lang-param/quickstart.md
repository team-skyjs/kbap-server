# Quickstart: kb-229-scan-lang-param

## 검증 실행

```bash
./gradlew :domain:member:test :domain:scan:test :app:api:test   # 변경 모듈 테스트
./gradlew build                                                  # 전체 빌드(최종 게이트)
```

## 수동 확인 (local 프로필)

```bash
./gradlew :app:api:bootRun   # SPRING_PROFILES_ACTIVE=local
```

```bash
# 1) lang 지정 스캔 — 응답 results[].name 이 en 번역인지 확인
curl -X POST 'http://localhost:8080/api/v1/scans?lang=en' \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"imagePath":"scan/1/test.jpg","items":[{"idx":0,"rawMenuName":"김치찌개"}]}'

# 2) 미지원 코드 → 400 COMMON-002
curl -X POST 'http://localhost:8080/api/v1/scans?lang=fr' ...   # 400 COMMON-002

# 3) lang 누락 → 400
curl -X POST 'http://localhost:8080/api/v1/scans' ...           # 400

# 4) 온보딩 — appLanguage 없이 성공, 넣어 보내도 무시되고 성공
# 5) 내 프로필 조회 — 응답에 appLanguage 키 없음
```

## 핵심 회귀 포인트

- 기존 회원(profile JSON 에 `"appLanguage"` 키 보유) 조회·수정·스캔이 깨지지 않는다 — `MemberProfileJson` 의 `@JsonIgnoreProperties(ignoreUnknown = true)` 가 지킨다(research R3). legacy JSON 을 읽는 테스트가 이를 고정한다.
- Swagger UI(`/swagger-ui`)에서 scans 의 `lang` 파라미터 노출, member 예시에서 `appLanguage` 소멸 확인.
