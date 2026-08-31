# Quickstart: 스캔 v2 — 서버 OCR·유사 폴백 (KB-319)

## 수동 확인

로컬 실행(유사 폴백은 no-op — 임베딩·벡터 미활성):

```bash
./gradlew :api:bootRun     # SPRING_PROFILES_ACTIVE=local
```

1. 로그인 → presign 발급 → S3 업로드 → 완료 신고 (기존 이미지 플로우).
2. **v2 스캔** — items 없이:

```bash
curl -X POST "http://localhost:8080/api/v1/scans?lang=en" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-API-Version: 2026.08.07' \
  -d '{"imagePath":"images/scan/2026/08/1_abc.jpg"}'
# results[].idx == null, 서버 추출 결과. miss 항목 similarFood 는 로컬에선 null(빈 미구성)
```

3. **v1 회귀** — 헤더 없이 기존 형식:

```bash
curl -X POST "http://localhost:8080/api/v1/scans?lang=en" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"imagePath":"...","items":[{"idx":0,"rawMenuName":"김치찌개"}]}'
# 종전 동작. items 누락 시 400 COMMON-002 유지 확인
```

4. **유사 폴백 실검증(dev)** — dev 프로필(`kbap.vector.enabled=true`·`kbap.llm.embedding.enabled=true`)로 미등록 메뉴 스캔 → `similarFood` 에 유사 음식·`imageRef` 공개 URL 확인. DocumentDB `$search` 문법 검증은 이 단계에서만 가능(로컬 재현 불가 — contracts/vector-food-document.md).

Swagger UI: "스캔" 태그에서 `X-API-Version` 헤더 파라미터·v2 동작 안내 확인.

## 테스트

```bash
./gradlew :api:test --tests "*ScanControllerTest"        # v1 회귀 + v2 분기·hit/miss·폴백 (fake searcher + Testcontainers)
./gradlew :api:test --tests "*SimilarFoodResolverTest"   # 임계·장애·빈 부재 폴백 (단위)
./gradlew :infra:llm:test                                 # 서버 OCR 프롬프트 분기 (단위)
./gradlew build                                           # 전체 (ArchUnit 포함)
```

## 롤백

DB 스키마 변경이 없으므로 서버 리비전 롤백만으로 되돌아간다. `kbap.vector.enabled=false` 로 유사 폴백만 끄는 부분 롤백도 가능(스캔 v2 자체는 유지 — miss 항목이 미등록 응답으로만 내려간다).
