# Quickstart — 메뉴 스캔 mock 슬라이스

이 기능을 빌드·테스트·실행하고 두 API를 확인하는 방법.

## 사전 조건
- JDK는 Gradle toolchain(Java 21)이 자동 프로비저닝(로컬 `JAVA_HOME` 무관).
- 로컬 실행 시 MySQL 필요(`local` 프로필). 테스트는 임베디드 H2(설정 불필요).

## 빌드 & 테스트 (TDD 루프)
```bash
./gradlew test                              # 전체 테스트
./gradlew :meogo-api:scan:test              # scan 순수 도메인
./gradlew :meogo-api:food:test              # food 순수 도메인
./gradlew :meogo-api:persistence:test       # RepositoryAdapter 저장/조회(H2)
./gradlew :meogo-api:application:test       # 유스케이스·mock seam
./gradlew :meogo-api:presentation:test      # web 계약(MockMvc) — 200/400, BaseResponse
```
헌법 I: 각 task는 **실패 테스트 먼저** 작성 → 최소 구현 → 리팩터.

## 로컬 실행
```bash
# DB 준비: docker compose up -d mysql mongo  (localhost:3306/meogo root/root, mongo 27017)
SPRING_PROFILES_ACTIVE=local ./gradlew :meogo-api:presentation:bootRun
```
- Flyway가 `V1`(scan)·`V2`(food)·`V3`(seed) 마이그레이션을 적용한다(스키마 owner = api). Hibernate `ddl-auto`는 `validate`(Flyway가 만든 스키마를 검증만).
- Swagger UI: `/swagger-ui.html`, OpenAPI JSON: `/v3/api-docs`(springdoc).
- **한글 쿼리 파라미터는 URL 인코딩이 필요**하다(raw 한글은 Tomcat이 400 거부). 아래 GET 예시는 `curl -G --data-urlencode`로 인코딩한다.

## API 1 — 스캔 제출 + mock 판정
```bash
curl -s -X POST http://localhost:8080/api/v1/menu-scans \
  -H 'Content-Type: application/json' \
  -d '{
    "items": [
      {"itemId":0,"rawMenuName":"된장찌개","boundingBox":{"x":0.1,"y":0.1,"width":0.5,"height":0.08}},
      {"itemId":1,"rawMenuName":"김치찌개","boundingBox":{"x":0.1,"y":0.2,"width":0.5,"height":0.08}},
      {"itemId":2,"rawMenuName":"공기밥","boundingBox":{"x":0.1,"y":0.3,"width":0.5,"height":0.08}},
      {"itemId":3,"rawMenuName":"콜라","boundingBox":{"x":0.1,"y":0.4,"width":0.5,"height":0.08}}
    ]
  }'
# → success:true, payload.scanId, payload.results[0..3] = SAFE/CAUTION/DANGER/UNKNOWN
```
검증 포인트: results가 itemId로 매칭, 4단계 모두 포함. 잘못된 요청(빈 items, itemId 중복, boundingBox 누락, width=0 등)은 400 + `success:false`.

## API 2 — 음식 상세 조회 (다국어)
한글 `menuName`은 URL 인코딩이 필요하므로 `curl -G --data-urlencode`를 쓴다.
```bash
D='http://localhost:8080/api/v1/foods/detail'

curl -s -G "$D" --data-urlencode 'menuName=된장찌개' --data-urlencode 'lang=en'
# → 200, payload.name="Doenjang Stew" + payload.ingredients[*].name(영어) + inclusionPercent + riskStatus(첫 재료 CAUTION, 나머지 SAFE)

curl -s -G "$D" --data-urlencode 'menuName=된장찌개' --data-urlencode 'lang=ja'
# → 200, payload.name="テンジャンチゲ" (일본어 번역본)

curl -s -G "$D" --data-urlencode 'menuName=된장찌개'                                   # lang 미지정
curl -s -G "$D" --data-urlencode 'menuName=된장찌개' --data-urlencode 'lang=xx'        # 미지원
# → 200, ko 폴백(payload.name="된장찌개" 등 ko 값)

curl -s -G "$D" --data-urlencode 'menuName=없는메뉴' --data-urlencode 'lang=en'
# → 400, success:false, message:"해당 음식 정보 없음" (미수록 메뉴 = 잘못된 요청)

curl -s "$D?menuName="
# → 400, success:false, message:"menuName은 필수입니다"
```

## 확인 체크리스트(Success Criteria 대응) — local bootRun 수동 검증 완료(2026-06-28)
- [x] N개 제출 → N개 결과 누락 0 (SC-001) — 4개 제출 → 4개 결과
- [x] 동일 메뉴명 중복 → itemId 매칭 정확 (SC-002) — web 계약 테스트
- [x] 4개+ 항목 → 4단계 모두 노출 (SC-003) — SAFE/CAUTION/DANGER/UNKNOWN
- [x] 잘못된 스캔 요청 100% 400 (SC-005) — 빈 items → 400
- [x] 저장 검증: scanId·항목·boundingBox·mock 결과 (SC-006) — repository/service 테스트
- [x] 음식 상세 다국어(lang별 번역 + 미지원 ko 폴백)·200/400 일관 (SC-007) — en/ja/ko·xx 폴백·미수록 400·blank 400
- [x] 상세 응답만으로 화면 구성 가능 (SC-008) — name·imageRef·ingredients[name·iconRef·inclusionPercent·riskStatus]
- (SC-004 체감 즉시: mock 동기 처리, 별도 측정 생략)
