# Quickstart: MySQL Testcontainers 통합 테스트 (KB-46)

## 전제

- DB-backed 통합 테스트는 **Docker(또는 호환 컨테이너 런타임)** 를 요구한다. 미가용 환경에서는 해당 테스트가 "유효한 Docker 환경 미탐지" 메시지로 명확히 실패한다.
- 순수 도메인 단위 테스트(Spring·DB 무관)는 컨테이너 없이 그대로 빠르게 실행된다.

## 실행

```bash
# 전체 테스트 (DB-backed 는 MySQL 8.4 컨테이너 자동 기동)
./gradlew test

# 모듈별
./gradlew :app:api:test
./gradlew :infra:persistence:test

# 마이그레이션 검증 크라운 테스트만 (P1)
./gradlew :app:api:test --tests "*MigrationValidationTest"
```

컨테이너는 **모듈 테스트 JVM 당 1회** 기동되어(Spring 컨텍스트 캐싱) 클래스 간 재사용된다. 로컬 반복 실행을 더 빠르게 하려면 Testcontainers 재사용(`~/.testcontainers.properties` 의 `testcontainers.reuse.enable=true`)을 옵트인할 수 있다(선택).

> ⚠️ **재사용 옵트인 시 주의**: `testcontainers.reuse.enable=true` 를 켜고 컨테이너 정의에 `.withReuse(true)` 를 주면, **테스트가 끝나도 컨테이너가 종료되지 않고 계속 떠 있는다**(Ryuk 가 정리하지 않음 → `docker ps` 에 상주). 다음 실행이 그 컨테이너에 재접속한다. 내리려면 `docker stop`/`docker rm` 로 직접 정리해야 한다. 그래서 이 프로젝트는 `.withReuse(true)` 를 **코드에 넣지 않으며**, 한 실행 내 재사용은 컨텍스트 캐싱이 이미 담당한다.

### 컨테이너 설정 (프로덕션 파리티)

`MySqlContainerConfig` 는 로컬 docker-compose 와 동일하게 다음을 고정한다:

- DB 이름·자격: `meogo` / `meogo` / `meogo` (`withDatabaseName`·`withUsername`·`withPassword`)
- 서버 문자셋: `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci` (마이그레이션이 만드는 `utf8mb4_unicode_ci` 와 일치)
- 서버 시간대: `--default-time-zone=+09:00` (KST). 명명 TZ(`Asia/Seoul`)는 MySQL tz 테이블 미로딩 시 부팅 실패하므로 오프셋으로 지정.

## 새 DB-backed 테스트 작성법

공통 컨테이너 설정 `MySqlContainerConfig` 는 `infra:persistence` 의 `testFixtures` 에 있다. DB-backed 테스트는 **`@Import(MySqlContainerConfig::class)` 로 컨테이너 설정만 주입**한다(웹·영속 테스트 동일 방식):

```kotlin
@SpringBootTest
@AutoConfigureMockMvc          // 웹/컨트롤러 테스트만
@Import(MySqlContainerConfig::class)
class FooTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    // ...
}
```

- `app:api` 테스트는 Flyway 가 켜져 있어(`flyway.enabled=true`) **운영 마이그레이션이 스키마·시드를 만든다.** 테스트 픽스처는 시드 마이그레이션과 충돌하지 않게 작성하고, 테스트 간 상태는 격리(트랜잭션 롤백/정리)한다.
- `infra:persistence` 테스트는 마이그레이션이 없으므로 Hibernate 가 스키마를 생성한다(어댑터 매핑 검증 목적).

## MySQL 버전 변경

이미지 태그는 `infra:persistence` testFixtures 의 `MySqlContainerConfig` 상수 **한 곳**에서 관리한다. 운영 MySQL 메이저 버전이 바뀌면 이 상수만 갱신한다.

## 트러블슈팅

- **"Could not find a valid Docker environment"** → Docker 데몬 미기동. 컨테이너 런타임을 켠다.
- **Flyway 마이그레이션 실패** → 마이그레이션 SQL 이 MySQL 8.4 에서 문제. 이 작업이 잡으려던 결함이다. 로컬 docker MySQL 에 직접 적용해 원인 확인(메모: `flyway-migration-validation-gap`).
- **`ddl-auto=validate` 실패** → 엔티티와 마이그레이션 스키마 드리프트. 어느 쪽이 정답인지 판단해 정합.
