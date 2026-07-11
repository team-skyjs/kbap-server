# Quickstart: 홈 화면 조회 (KB-111)

로컬에서 기능을 확인하는 최소 시나리오. 상세 규범은 [plan.md](./plan.md)·[contracts/home-api.md](./contracts/home-api.md).

## 전제

- 로컬 docker 스택 기동(`docker-compose` — MySQL 8.4 + Redis + Mongo), `SPRING_PROFILES_ACTIVE=local`.
- 새 마이그레이션(`scan_history`) 반영을 위해 로컬 DB 는 `local-dev-docker-stack` 절차대로 재적용(신규 마이그레이션은 로컬 DB DROP+CREATE 후 부팅으로 검증).
- 앱 실행: IntelliJ 또는 `./gradlew :app:api:bootRun`.

## 시나리오

### 1. 비회원 홈

```bash
curl -s http://localhost:8080/api/v1/home | jq
```
기대: `avoidedSubstances=null`, `recentScans=null`, `popularFoods` 최대 5개(영어), 위험도 `UNKNOWN`.

### 2. 회원 로그인 → 온보딩 → 홈

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"idToken":"valid-token"}' \
  | jq -r '.payload.accessToken')

# 온보딩(기피 성분·언어 설정)
curl -s -X POST http://localhost:8080/api/v1/members/me/onboarding \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"nickname":"길동","avoidanceSubstanceCodes":["EGG","MILK"],"countryCode":"US","appLanguage":"ja"}'

curl -s http://localhost:8080/api/v1/home -H "Authorization: Bearer $TOKEN" | jq
```
기대: `avoidedSubstances` = EGG·MILK(일본어 이름), `popularFoods`(일본어·회원 기피 기준 위험도), `recentScans=[]`(아직 스캔 없음).

### 3. 스캔 → 최근 스캔 반영

```bash
curl -s -X POST http://localhost:8080/api/v1/scans \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"idx":0,"rawMenuName":"비빔밥"},{"idx":1,"rawMenuName":"김치찌개"}]}'

curl -s http://localhost:8080/api/v1/home -H "Authorization: Bearer $TOKEN" | jq '.payload.recentScans'
```
기대: 방금 스캔한 READY 음식이 최근 스캔 상위에. 같은 메뉴를 다시 스캔해도 중복 없이 1건, 최신순 정렬.

### 4. 무효 토큰 = 401

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/home -H "Authorization: Bearer garbage"
```
기대: `401`.

## 자동 테스트로 검증되는 항목 (구현 후)

- 유스케이스 단위(페이크 repo): 언어 결정·비회원 분기·최근 스캔 dedup/정렬/limit·기피 프로필 반영.
- 컨트롤러 통합(MockMvc + Testcontainers): 회원/비회원/무효토큰(401)/기피 없음([])/스캔 없음([])/스캔 후 최근 반영/같은 메뉴 중복 제거.
- 회귀: 기존 food 목록·검색·상세·scan 테스트가 프로바이더 교체 후에도 통과.
