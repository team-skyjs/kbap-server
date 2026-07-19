# Research: prod Redis TLS 필수 대응 (KB-169)

## R1. TLS 활성화 프로퍼티

- **Decision**: `spring.data.redis.ssl.enabled: true` (Boot 4.1)
- **Rationale**: Boot 3.1 부터 Redis 프로퍼티는 `spring.data.redis.*` 네임스페이스이고 ssl 은 `ssl.enabled` 하위 키다(구 `spring.redis.ssl` boolean 은 폐기 계보). Lettuce 자동구성이 이 값으로 `RedisStandaloneConfiguration` 접속에 TLS 를 적용하므로 **코드 변경이 전혀 필요 없다** — `:infra:redis` 의 `RedisRefreshTokenStore` 는 `StringRedisTemplate` 만 쓰고 커넥션 구성은 전부 Boot 자동구성 소관.
- **Alternatives considered**: Lettuce `ClientOptions` 커스텀 빈(기각 — 프로퍼티 한 줄로 되는 일에 조립 코드 추가), `rediss://` URL 스킴(기각 — 기존 host/port 분리 선언을 URL 로 재작성해야 해 diff 가 커짐).

## R2. 선언 위치 — 4개 프로필 yml vs 베이스 application.yml

- **Decision**: **4개 프로필 yml(local·dev·staging·prod)의 기존 redis 블록에 각각 선언**한다.
- **Rationale**: (1) redis 블록 자체(host·port)가 이미 프로필별 선언이다 — ssl 만 베이스로 빼면 접속 설정이 두 파일로 갈라진다. (2) 베이스 단일 선언은 기본값이 하나뿐이라 "배포 환경 true·local false" 를 표현하려면 결국 프로필 오버라이드가 필요해 이득이 없다. (3) 사용자 지시 "모든 환경에 동일하게 적용" 은 4개 프로필 전부에 같은 항목이 명시 선언되는 형태로 충족한다(FR-002 검증도 파일 비교로 단순).
- **Alternatives considered**: 베이스 yml 단일 선언(기각 — 위), prod 만 추가(기각 — 사용자 지시로 배제, Jira DoD 의 staging 확인 항목도 전 환경 적용으로 대체됨).

## R3. 값 — 하드코딩 vs 환경변수 주입

- **Decision**: `enabled: ${REDIS_SSL_ENABLED:기본값}` — 기본값은 dev·staging·prod=`true`, local=`false`.
- **Rationale**: (1) 기존 관례를 그대로 따른다 — 같은 블록의 `${REDIS_HOST}`/`${REDIS_PORT:6379}`, local 만 `${REDIS_HOST:localhost}` 처럼 local 에 개발 친화 기본값을 주는 구조. (2) prod 는 기본값 true 라 **인프라 환경변수 추가 없이 배포만으로 장애가 해소**된다(티켓 DoD 취지). (3) local 기본 false 는 평문 docker Redis(localhost:6379) 개발 흐름을 지키고, TLS 로컬 검증이 필요하면 `REDIS_SSL_ENABLED=true` 로 켠다. (4) dev 홈서버 Redis 가 평문으로 판명되면 커밋 없이 `REDIS_SSL_ENABLED=false` 로 뒤집는 탈출구가 된다.
- **Alternatives considered**: 전 환경 `true` 하드코딩(기각 — local 평문 docker 접속 즉시 파괴), 전 환경 기본 false + 인프라에서 env 주입(기각 — yml 만으로 prod 가 고쳐지지 않아 티켓 취지 역행).

## R4. 기존 테스트 영향

- **Decision**: 기존 통합·시나리오 테스트 무변경.
- **Rationale**: 테스트 컨텍스트는 `app/api/src/test/resources/application.yml`(테스트 classpath 가 main 의 `application.yml` 을 대체)을 쓰고 활성 프로필이 없어 `application-{profile}.yml` 을 로드하지 않는다 — Testcontainers Redis(평문) 접속은 ssl 기본값 false 그대로다.
- **Alternatives considered**: 테스트 yml 에 ssl:false 명시(기각 — 기본값이 이미 false, 무의미한 줄).

## R5. Test-First 충족 방식 (헌법 I)

- **Decision**: 신규 리소스 가드 테스트 `RedisSslConfigTest`(`:app:api`, BehaviorSpec) — classpath 의 4개 프로필 yml 을 SnakeYAML 로 파싱해 (a) `spring.data.redis.ssl.enabled` 키가 4개 프로필 전부에 존재, (b) 값이 local=`${REDIS_SSL_ENABLED:false}`·배포 3환경=`${REDIS_SSL_ENABLED:true}` 임을 검증.
- **Rationale**: TLS 접속 성립 자체는 TLS Redis 인프라 없이는 테스트 불가(Testcontainers Redis 기본 이미지는 평문). 설정 존재·값을 가드하는 리소스 테스트가 이 작업의 검증 가능한 최대치이며, KB-163 `MigrationLayoutTest`(리소스 가드) 선례를 따른다. 프로필 yml 은 main 리소스라 테스트 classpath 에서 이름 충돌 없이 `ClassPathResource("application-prod.yml")` 로 읽힌다. 실제 TLS 접속 검증은 배포 후 런북(quickstart.md)으로 커버.
- **Alternatives considered**: TLS 구성 Redis Testcontainers 통합 테스트(기각 — 커스텀 TLS 인증서·이미지 구성 비용이 설정 2줄짜리 작업 대비 과도, 얻는 확신도 Boot 자동구성 동작 재검증에 불과).

## R6. 범위 확인 — 클러스터 모드

- **Decision**: `spring.data.redis.cluster.*` 전환은 하지 않는다(범위 밖).
- **Rationale**: prod ElastiCache 는 클러스터 모드 활성·샤드 1 — 단일 샤드는 전 슬롯을 한 노드가 소유해 configuration endpoint 로의 standalone 접속이 동작하며, 티켓이 진단한 장애 원인은 TLS 불일치 하나다. 클러스터 클라이언트 전환은 샤드 증설 시점의 별도 이슈.
