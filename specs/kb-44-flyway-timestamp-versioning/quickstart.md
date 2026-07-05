# Quickstart: 로컬 docker MySQL 로 timestamp 버전 + out-of-order 검증

목적: 자동 테스트가 없는 이 작업(문서+설정)을 **로컬 docker MySQL 부팅 실측**으로 검증한다.
전제: `docker-compose` 로 `meogo-mysql`(8.4) 기동, `SPRING_PROFILES_ACTIVE=local`. (앱은 IntelliJ 로 실행 중일 수 있음 — 8080 점유 주의, broad `pkill` 금지.)

## 0. 준비

```bash
# docker MySQL 기동 확인
docker ps | grep meogo-mysql
```

`spring.flyway.out-of-order: true` 가 `app/api/src/main/resources/application.yml` 에 반영된 상태여야 한다.

## 1. 기존 정수 이력을 baseline 으로 사용 (리셋 불필요)

로컬 MySQL 에 이미 `V1`~`V10` 이 적용돼 있으면 **그 상태가 곧 baseline** 이다 — `flyway_schema_history` 를 지우거나 DB 를 리셋하지 않는다(지우면 Flyway 가 전체 재적용을 시도해 실패한다). 아래 probe 를 그 위에 얹어 검증한다.

현재 이력 확인:

```sql
SELECT installed_rank, version, description, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
-- version 1..10 이 순서대로 success=1  ← 이게 baseline
```

> 완전히 처음부터 재현하고 싶을 때만(선택) DB 를 DROP+CREATE 후 부팅해 정수 이력을 다시 만든다(memory: flyway-migration-validation-gap). 평상시엔 불필요.

## 2. 신규 timestamp 마이그레이션 (정상 순서) 검증

미래(=현재 시각) timestamp 로 더미 마이그레이션을 임시 추가한다. **검증용이며 커밋하지 않는다.**

```
app/api/src/main/resources/db/migration/V2026.07.05.14.30.12__qs_probe_forward.sql
```
```sql
CREATE TABLE qs_probe_forward (id BIGINT PRIMARY KEY);
```

재부팅 → 확인:
```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- ... 10, 2026.07.05.14.30.12(qs_probe_forward) success=1  ← 기존 이력 뒤에 정상 적용
```

## 3. 과거 timestamp (out-of-order) 검증 — 핵심

이미 적용된 최신(2번의 `2026.07.05.14.30.12`)보다 **과거** timestamp 로 또 하나 추가한다.

```
app/api/src/main/resources/db/migration/V2026.07.05.09.00.00__qs_probe_ooo.sql
```
```sql
CREATE TABLE qs_probe_ooo (id BIGINT PRIMARY KEY);
```

재부팅 → 확인:
```sql
SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- installed_rank 순서상 09.00.00(qs_probe_ooo) 가 14.30.12 보다 "뒤"(나중 rank)에 기록됨
-- = out-of-order 로 적용되었고 부팅이 막히지 않음  ← FR-005/SC-005 충족
```
- **out-of-order=false 였다면** 이 단계에서 Flyway validate 실패로 부팅이 거부된다(대조군).

## 4. 기존 checksum 무결성 확인

```sql
SELECT version, checksum FROM flyway_schema_history WHERE version REGEXP '^[0-9]+$';
-- V1~V10 의 checksum 이 1번 baseline 과 동일 → 기존 파일 불변 확인 (INV-1/SC-003)
```
Flyway validate 가 부팅 시 통과했다는 것 자체가 기존 checksum 무결성을 보증한다.

## 5. 정리 (필수)

검증용 probe 마이그레이션과 테이블을 제거해 원상 복구한다.

```bash
rm app/api/src/main/resources/db/migration/V2026.07.05.14.30.12__qs_probe_forward.sql
rm app/api/src/main/resources/db/migration/V2026.07.05.09.00.00__qs_probe_ooo.sql
```
```sql
DROP TABLE IF EXISTS qs_probe_forward;
DROP TABLE IF EXISTS qs_probe_ooo;
DELETE FROM flyway_schema_history WHERE description LIKE 'qs probe%';
```

## 통과 기준 (spec Success Criteria 대응)

- 2번 정상 적용 → SC-004(a)
- 3번 out-of-order 정상 적용, 부팅 안 막힘 → SC-004(b), SC-005
- 4번 기존 checksum 통과 → SC-003, SC-004(c)
