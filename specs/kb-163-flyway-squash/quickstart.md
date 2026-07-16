# Quickstart: Flyway 스쿼시 검증 & 전환 런북 (KB-163)

## 1. 스키마 도출·검증 (구현 중, 로컬 docker)

```bash
# 기준 DB: 구 22개 마이그레이션 적용
docker run -d --name kbap-old -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=kbap -p 3307:3306 mysql:8
for f in $(ls app/api/src/main/resources/db/migration/V*.sql | sort); do
  mysql -h127.0.0.1 -P3307 -uroot -proot kbap < "$f"
done
mysqldump -h127.0.0.1 -P3307 -uroot -proot --no-data --skip-comments kbap > /tmp/old-schema.sql
mysqldump -h127.0.0.1 -P3307 -uroot -proot --no-create-info --skip-comments kbap food food_avoidance_substance > /tmp/demo-data.sql

# 새 init 적용 DB 와 diff (flyway_schema_history 제외, AUTO_INCREMENT 정규화 후 diff = 0 이어야 함)
docker run -d --name kbap-new -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=kbap -p 3308:3306 mysql:8
mysql -h127.0.0.1 -P3308 -uroot -proot kbap < app/api/src/main/resources/db/migration/V*__init_schema.sql
mysqldump -h127.0.0.1 -P3308 -uroot -proot --no-data --skip-comments kbap > /tmp/new-schema.sql
diff <(sed 's/ AUTO_INCREMENT=[0-9]*//' /tmp/old-schema.sql) <(sed 's/ AUTO_INCREMENT=[0-9]*//' /tmp/new-schema.sql)
```

## 2. 테스트

```bash
./gradlew :app:api:test          # MigrationLayoutTest·AvoidanceCatalogSeedSyncTest 포함
./gradlew test                   # 전체 (통합 테스트가 새 마이그레이션으로 스키마 생성 + validate)
```

## 3. 신규(빈) DB — 프로필별 확인

```bash
# local/dev: 스키마 + 마스터 81종 + 데모 음식 10건
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
# prod 상당(베이스 locations): 데모 0건 확인은 MigrationLayoutTest 가드 + 수동 확인
```

## 4. 홈서버(dev) DB 전환 런북 — 데이터 보존 (FR-005)

> 전제: 구 22개 마이그레이션이 전부 적용된 상태. **drop 금지.**

```bash
# 1) 백업
mysqldump -h<HOST> -u<USER> -p kbap > backup-$(date +%F).sql

# 2) 스키마 일치 확인 (섹션 1의 old-schema 덤프와 diff = 0)
mysqldump -h<HOST> -u<USER> -p --no-data --skip-comments kbap > /tmp/dev-schema.sql

# 3) 이력 장부 제거 (데이터 테이블 무접촉)
mysql -h<HOST> -u<USER> -p kbap -e "DROP TABLE flyway_schema_history;"

# 4) 1회 부팅 — 재기준선 (확정 버전: 데모 시드 2026.07.16.21.38.43)
SPRING_PROFILES_ACTIVE=dev \
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
SPRING_FLYWAY_BASELINE_VERSION=2026.07.16.21.38.43 \
./gradlew :app:api:bootRun
# Flyway 가 baseline 행만 기록하고 init·마스터·데모 전부 스킵 → 기존 데이터 무접촉

# 5) 검증: 전환 전후 행 수 일치 + 정상 부팅
mysql -h<HOST> -u<USER> -p kbap -e \
 "SELECT 'member',COUNT(*) FROM member UNION ALL SELECT 'food',COUNT(*) FROM food \
  UNION ALL SELECT 'bookmark',COUNT(*) FROM bookmark UNION ALL SELECT 'scan_history',COUNT(*) FROM scan_history \
  UNION ALL SELECT 'uploaded_image',COUNT(*) FROM uploaded_image UNION ALL SELECT 'avoidance_substance',COUNT(*) FROM avoidance_substance;"
```

이후 부팅부터는 baseline 플래그를 제거한다(신규 마이그레이션만 정상 적용).

> **리허설 결과 (2026-07-16, docker MySQL 8.4)**: 구 22개 적용 + 더미 회원 2·북마크 2·스캔이력 1 상태에서 위 절차 수행 →
> `Successfully baselined schema with version: 2026.07.16.21.38.43`, 전 테이블 행 수 전후 100% 일치, 데모 시드 미재적용.
> 플래그 제거 후 2차 부팅도 `Schema is up to date. No migration necessary` 로 정상 기동 확인.

> **스키마 diff 참고**: 신구 덤프 비교 시 `AUTO_INCREMENT=<n>` 외에 `member` 테이블 컬럼의
> `CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` 표기 차이가 나온다 — 구 DB 는 암묵(테이블 기본), 재적용 DB 는 명시로
> 기록될 뿐 실제 charset·collation 은 동일하다. 정규화: `sed 's/CHARACTER SET utf8mb4 COLLATE/COLLATE/'`.

## 5. 로컬 DB

개인 소유 — 간단히 drop 후 재생성(`DROP DATABASE kbap; CREATE DATABASE kbap;` 후 local 프로필 부팅)하거나, 데이터를 남기려면 섹션 4 절차를 동일 적용.
