# Tasks: prod Redis TLS 필수 대응 — 전 환경 동일 TLS 설정

**Input**: plan.md·research.md (specs/kb-169-redis-tls/)

**Tests**: 헌법 원칙 I — 리소스 가드 테스트 선행(Red 확인 후 Green).

## Phase 1: US1+US2 — TLS 설정 전 환경 동일 적용 (P1)

- [X] T001 [US1] Red: `app/api/src/test/kotlin/com/kbap/app/api/config/RedisSslConfigTest.kt` 신규 — 4개 프로필 yml 을 SnakeYAML 로 파싱해 `spring.data.redis.ssl.enabled` 가 dev·staging·prod=`${REDIS_SSL_ENABLED:true}`, local=`${REDIS_SSL_ENABLED:false}` 로 선언되어 있음을 검증(BehaviorSpec·한국어 given/when/then). 실행해 **실패(Red) 확인**.
- [X] T002 [US1][US2] Green: `app/api/src/main/resources/application-{local,dev,staging,prod}.yml` redis 블록에 `ssl.enabled` 동일 선언 추가(local 기본 false, 배포 3환경 기본 true) → T001 통과 확인.
- [X] T003 검증: `./gradlew :app:api:test` 회귀 무손상 확인(quickstart §1).

## Dependencies

T001 → T002 → T003 (순차 — Test-First).
