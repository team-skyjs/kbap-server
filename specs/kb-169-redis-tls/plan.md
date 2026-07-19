# Implementation Plan: prod Redis TLS 필수 대응 — 전 환경 동일 TLS 설정

**Branch**: `kb-169-redis-tls` | **Date**: 2026-07-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-169-redis-tls/spec.md`

## Summary

prod ElastiCache Redis(전송 중 암호화 필수)에 API 앱이 평문 TCP 로 접속을 시도해 연결이 성립하지 않고, refresh token 저장 실패로 로그인 API 가 500 을 반환한다. 해결은 Boot 4.1 프로퍼티 `spring.data.redis.ssl.enabled` 활성화이며, 사용자 지시에 따라 **4개 환경 프로필(local·dev·staging·prod) 전부에 동일한 형태로 선언**한다. 기존 redis 블록의 `${REDIS_HOST}`/`${REDIS_PORT:6379}` 관례를 따라 **`ssl.enabled: ${REDIS_SSL_ENABLED:기본값}`** 으로 두되, 기본값만 배포 3환경(dev·staging·prod)=`true`, local=`false`(평문 docker Redis)로 한다 — 환경변수로 커밋 없이 뒤집을 수 있는 탈출구(예: dev 홈서버 Redis 가 평문으로 판명되는 경우)를 남긴다. **애플리케이션 코드 변경 0줄** — Lettuce TLS 는 Boot 자동구성이 프로퍼티만으로 적용한다. Test-First 는 KB-163 `MigrationLayoutTest` 선례의 **리소스 가드 테스트**(4개 프로필 yml 에 ssl 선언 존재·기본값 검증)로 충족한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (변경 없음 — yml 리소스만 수정)

**Primary Dependencies**: Spring Boot 4.1 data-redis(Lettuce) — `spring.data.redis.ssl.enabled` 프로퍼티(Boot 3.1+ 부터 `spring.data.redis.*` 네임스페이스). 신규 의존성 0.

**Storage**: Redis(refresh token, `:infra:redis` `RedisRefreshTokenStore`) — 접속 방식만 TLS 로 변경, 데이터·키 구조 불변. MySQL·Flyway 무관(마이그레이션 0).

**Testing**: Kotest BehaviorSpec 리소스 가드 테스트(`:app:api` — classpath 의 4개 프로필 yml 을 SnakeYAML 로 파싱해 ssl 선언 검증). 기존 통합 테스트는 테스트 전용 `app/api/src/test/resources/application.yml` 이 프로필 yml 을 로드하지 않으므로 무영향(Testcontainers Redis 는 평문 그대로).

**Target Platform**: `:app:api` bootJar (prod ElastiCache — 클러스터 모드 활성·샤드 1). `:app:batch` 는 Redis 미사용 — 범위 밖.

**Project Type**: web-service 설정(yml) 변경 — 프로덕션 코드 0줄.

**Performance Goals**: 해당 없음(TLS 핸드셰이크 오버헤드는 커넥션 수립 시 1회 — Lettuce 는 커넥션을 재사용).

**Constraints**: 로그인 API 계약(요청·응답·에러코드) 무변경. local 개발 흐름(평문 docker Redis) 비파괴.

**Scale/Scope**: yml 4파일 각 2줄 + 가드 테스트 1파일. 총 변경 ~5파일.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ Pass | 리소스 가드 테스트(`RedisSslConfigTest`)를 먼저 작성해 Red(ssl 키 부재) 확인 후 yml 추가로 Green. KB-163 `MigrationLayoutTest` 선례와 동일한 패턴. |
| II. Bounded Contexts | ✅ Pass | 도메인 모듈 변경 0 — 접촉 없음. |
| III. Layered Dependency | ✅ Pass | 모듈 그래프 변경 0. |
| IV. Persistence Encapsulation | ✅ Pass | 엔티티·리포지토리 변경 0. |
| V. Language Policy | ✅ Pass | 무관. |

**Post-Phase 1 재평가**: 설계 변화 없음 — 전 게이트 유지 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-169-redis-tls/
├── plan.md              # This file
├── research.md          # Phase 0 output — 프로퍼티·배치 방식 결정
├── quickstart.md        # Phase 1 output — 배포 후 검증 런북
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

`data-model.md`·`contracts/` 는 생성하지 않는다 — 엔티티·데이터 변경 0, 외부 인터페이스(API 계약) 변경 0 인 순수 설정 작업이다.

### Source Code (repository root)

```text
app/api/src/main/resources/
├── application-local.yml      # redis 블록에 ssl.enabled: ${REDIS_SSL_ENABLED:false} 추가
├── application-dev.yml        # redis 블록에 ssl.enabled: ${REDIS_SSL_ENABLED:true} 추가
├── application-staging.yml    # redis 블록에 ssl.enabled: ${REDIS_SSL_ENABLED:true} 추가
└── application-prod.yml       # redis 블록에 ssl.enabled: ${REDIS_SSL_ENABLED:true} 추가

app/api/src/test/kotlin/com/kbap/app/api/config/
└── RedisSslConfigTest.kt      # 신규 — 4개 프로필 yml 의 ssl 선언 리소스 가드(BehaviorSpec)
```

**Structure Decision**: 변경은 `:app:api` 리소스 4파일 + 테스트 1파일로 한정한다. 베이스 `application.yml` 단일 선언(전 프로필 상속)은 기본값을 하나로 강제해 local 평문 docker 와 충돌하므로 기각 — 프로필별 선언이 기존 redis 블록(host·port 도 프로필별 선언) 구조와도 일치한다. 상세 대안 비교는 research.md.

## Complexity Tracking

위반 없음 — 해당 없음.
