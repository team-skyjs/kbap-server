# Quickstart: 메뉴 스캔 정제 검증

## 전제

- 로컬 docker MySQL 8.4 (`meogo-mysql`) — 통합 테스트는 MySQL Testcontainers를 자동 기동
- `food` 테이블에 완성(READY) 시드 존재
- 실제 LLM 호출 검증엔 `UPSTAGE_ENABLED=true` + `UPSTAGE_API_KEY`

## 1. 단위·통합 테스트

```bash
./gradlew :core:kernel:test        # 정규화기(혼합 로마자·기호·비한글 빈 키), InterpretedName
./gradlew :core:food:test          # Food.incomplete, isReady, overallRisk 가 미완성이면 UNKNOWN
./gradlew :core:scan:test          # MenuItemMatch(Matched/Unmatched)
./gradlew :infra:llm:test          # 프롬프트 조립(번호·개수), 배열 파싱(NOT_FOOD·null·길이불일치·코드펜스)
./gradlew :infra:persistence:test  # matchKey 배치 조회·동음이의 최소 id·createIncomplete dedup·serving gate(목록·검색·상세)
                                   # + kernel matchKey ↔ SQL 생성 컬럼 동등성 sync 테스트
./gradlew :application:client:test # 라우팅·폴백·degraded·한 스캔 내 중복 생성 방지
./gradlew :app:api:test            # MockMvc e2e + SC-001 회귀 + 요청 검증(400)
./gradlew build                    # 전체(ArchUnit 경계 포함)
```

## 2. Flyway 실검증 (필수 — 테스트가 못 잡는 결함)

마이그레이션은 테스트에서 실행되지 않는다(Testcontainers는 엔티티로 스키마 생성). **생성 컬럼의 collation 결함은 실제 MySQL에서만 드러난다.**

```bash
DB=kb90_check
docker exec -i meogo-mysql sh -c "mysql -uroot -proot -e \"DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4\""
cat $(ls app/api/src/main/resources/db/migration/*.sql | sort) \
  | docker exec -i meogo-mysql sh -c "mysql -uroot -proot --default-character-set=utf8mb4 $DB"
# 기대: 에러 없음, food.korean_match_key 생성, menu_scan/scanned_menu_item 없음
docker exec -i meogo-mysql sh -c "mysql -uroot -proot -e \"DROP DATABASE $DB\""
```

## 3. 실제 LLM 스모크 (Upstage solar-pro)

로컬 개발 DB를 건드리지 않도록 **임시 DB + 다른 포트**로 띄운다.

```bash
docker exec -i meogo-mysql sh -c "mysql -uroot -proot -e 'CREATE DATABASE meogo_smoke CHARACTER SET utf8mb4'"

SPRING_PROFILES_ACTIVE=local \
DB_URL=jdbc:mysql://localhost:3306/meogo_smoke \
UPSTAGE_ENABLED=true UPSTAGE_API_KEY=<키> SERVER_PORT=8081 \
./gradlew :app:api:bootRun
```

```bash
curl -s -X POST localhost:8081/api/v1/menu-scans -H 'Content-Type: application/json' -d '{"items":[
  {"itemId":10,"rawMenuName":"김치찌개 kimchi jjigae"},
  {"itemId":20,"rawMenuName":"원산지 : 중국"},
  {"itemId":30,"rawMenuName":"된장찌게 8,000"},
  {"itemId":40,"rawMenuName":"MacBook Air F9"},
  {"itemId":50,"rawMenuName":"우주라면"}]}' | python3 -m json.tool
```

**기대**: `degraded=false`, 결과 3건 — 10·30은 `MATCHED`(오탈자 교정 포함), 50은 `UNMATCHED`+신규 `foodId`, 20·40은 **제외**.

이어서 확인:
```bash
curl -s "localhost:8081/api/v1/foods?lang=ko"                # 우주라면 미포함 (serving gate)
curl -s "localhost:8081/api/v1/foods/search?keyword=우주&lang=ko"  # 우주라면 미포함 (serving gate)
# 같은 우주라면 재스캔 → 같은 foodId, food 행 1개 (dedup)
```

정리: 앱 종료 후 `DROP DATABASE meogo_smoke`.

> `UPSTAGE_ENABLED`를 켜지 않으면 interpreter 빈이 생성되지 않아 **폴백 경로**로 동작하고 `degraded=true`가 온다. 로컬 개발 DB(`meogo`)로 띄우면 `menu_scan`·`scanned_menu_item`이 DROP되고 `food.content_status`가 추가된다(의도된 마이그레이션).
