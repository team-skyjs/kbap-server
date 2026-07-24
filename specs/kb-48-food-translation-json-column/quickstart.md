# Quickstart & Verification: 음식 번역결과 JSON 칼럼 통합 (KB-48)

구현 완료 후 아래로 스펙 성공기준(SC-001~004)을 검증한다.

## 0. 사전

- 브랜치: `kb-48-food-translation-json-column` (최신 develop #26 기반).
- 로컬 DB: docker MySQL(`meogo/meogo`), 앱은 `SPRING_PROFILES_ACTIVE=local`.
- 테스트는 H2(create-drop, flyway off) — 마이그레이션은 로컬 MySQL DROP+CREATE 부팅으로 별도 검증(테스트에서 안 돎).

## 1. 빌드·테스트 (전 층 그린)

```bash
./gradlew :core:food:test          # FoodContent 번역 폴백(name/description) 단위
./gradlew :infra:persistence:test  # FoodRepositoryAdapter JSON 라운드트립·폴백(H2)
./gradlew :application:client:test # GetFoodDetailUseCase 계약 불변(번역 포트 제거)
./gradlew :app:api:test            # FoodDetail* web 계약 회귀(수정 없이 통과해야 함)
./gradlew build                    # ArchUnit 모듈 경계 포함 전체
```

기대: 삭제된 번역 포트/엔티티/리포지토리 참조가 사라지고 전 테스트 그린. 특히 **web 계약 테스트는 수정 없이 통과**(SC-001).

## 2. 마이그레이션 무손실 이행 검증 (로컬 MySQL) — SC-002/SC-003

```sql
-- (마이그레이션 적용 전 스냅샷) 원본 번역 쌍 집합 캡처
SELECT food_id, lang_code, name    FROM food_name_translation        WHERE status='ACTIVE' ORDER BY food_id, lang_code;
SELECT food_id, lang_code, content FROM food_description_translation WHERE status='ACTIVE' ORDER BY food_id, lang_code;
```

DROP+CREATE 로 DB 재생성 후 앱 부팅(Flyway V1→V10 적용). 이행 후:

```sql
-- (A) JSON 맵을 펼쳐 원본과 동일 집합인지 대조
SELECT f.id, jt.k AS lang_code, jt.v AS name
FROM food f, JSON_TABLE(f.name_translations, '$.*' COLUMNS(v VARCHAR(255) PATH '$')) jt;  -- 값 확인
-- 키 목록:
SELECT id, JSON_KEYS(name_translations) FROM food;
SELECT id, JSON_KEYS(description_translations) FROM food;

-- (B) 레거시 테이블 부재 확인 (SC-003)
SHOW TABLES LIKE 'food_name_translation';         -- 0 rows
SHOW TABLES LIKE 'food_description_translation';   -- 0 rows
```

기대: 각 음식의 JSON 키/값이 (A) 스냅샷과 정확히 일치(누락·변형 0), 번역 0건 음식은 `{}`, 레거시 테이블 없음.

## 3. 응답 계약 동일성 (부팅 후) — SC-001

```bash
# 번역 존재 언어
curl "localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=en"
# 지원 언어이나 번역 부재 → ko 폴백
curl "localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=ru"
# 미지정 → ko
curl "localhost:8080/api/v1/foods/detail?menuName=된장찌개"
# 미지원 코드 → 400 + 지원 목록
curl -i "localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=fr"
```

기대: `payload.name`·`description` 값이 마이그레이션 이전과 동일. 폴백/400 동작 불변([contracts/food-detail-api.md](./contracts/food-detail-api.md)).

## 4. 쿼리 절감 확인 — SC-004

- 비-ko 요청 1건 처리 시 SQL 로그에 `food_name_translation`/`food_description_translation` 대상 SELECT 가 **없어야** 한다(테이블 자체가 없음). 번역은 `food` 행 로드에 포함.
- (선택) `spring.jpa.show-sql=true` 또는 p6spy 로 상세조회 1회의 SELECT 수가 기존보다 최대 2회 감소함을 확인.

## 완료 기준(Definition of Done)

- [ ] 전 층 테스트 그린 + `./gradlew build`(ArchUnit 포함) 통과.
- [ ] web 계약 테스트 **무수정** 통과(SC-001).
- [ ] 로컬 MySQL 이행 후 JSON 키/값 = 원본 스냅샷(SC-002), 레거시 테이블 부재(SC-003).
- [ ] 비-ko 상세조회에 번역 테이블 SELECT 0회(SC-004).
- [ ] 삭제 대상(번역 엔티티·리포지토리·포트 메서드) 잔존 참조 없음.
