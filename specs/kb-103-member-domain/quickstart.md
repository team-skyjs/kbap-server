# Quickstart: kb-103-member-domain

## 빌드·테스트

```bash
./gradlew :core:member:test                 # 도메인 단위 테스트 (Kotest BehaviorSpec)
./gradlew :infra:persistence:test           # 영속 어댑터 통합 테스트 (MySQL Testcontainers)
./gradlew build                             # 전체 (ArchUnit ModuleBoundaryTest 포함)
```

- 통합 테스트는 Docker 데몬이 떠 있어야 한다(MySQL 8.4 Testcontainers, `MySqlContainerConfig`).

## Flyway 마이그레이션 실측 (필수 — 테스트에선 마이그레이션이 돌지 않는다)

```bash
docker compose up -d meogo-mysql
docker exec -i meogo-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS meogo; CREATE DATABASE meogo;"
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun   # 부팅 후 flyway_schema_history 확인, 8080 사용 중이면 IntelliJ 앱 종료 요청
```

## 산출물 위치

- 도메인: `core/member/src/main/kotlin/com/meogo/core/member/`
- 영속: `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/member/`
- 마이그레이션: `app/api/src/main/resources/db/migration/V<timestamp>__create_member_tables.sql`
- 테스트: 각 모듈 `src/test/kotlin` 미러 경로

## 수동 확인 (선택)

이 기능은 HTTP API 가 없다. 어댑터 동작은 통합 테스트가 전부이며, 신원 해소 시나리오는 `MemberIdentityResolverTest`(fake repository) 로 확인한다.
