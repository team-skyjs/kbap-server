# Quickstart: 회원 프로필 JSON 컬럼 평탄화 (KB-297)

## 검증 명령

```bash
# member 도메인 단위 테스트
./gradlew :common:test --tests "com.kbap.common.domain.member.*"

# 백필 마이그레이션 + 프로필 API 통합 테스트 (Testcontainers MySQL, Flyway on + ddl-auto=validate)
./gradlew :api:test --tests "com.kbap.api.member.*" --tests "com.kbap.api.migration.*"

# 전체 회귀 (SC-002 — 온보딩·프로필 수정·조회 포함 전 테스트)
./gradlew build
```

## 수동 확인 포인트

1. `:api:test` 는 Flyway 전체 마이그레이션으로 스키마를 만들고 `ddl-auto=validate` 가 엔티티↔스키마 정합을 검증한다 — 컬럼 정의 불일치는 부팅 실패로 드러난다.
2. 백필 검증은 `MemberProfileBackfillTest`: schema 마이그레이션까지 적용된 DB 에 JSON 행을 시드하고 backfill SQL 을 실행해 신규 컬럼 4종 결과를 대조한다(legacy 선행 슬래시·null 국가·빈/결손 코드 배열·소프트 삭제 회원 케이스 포함).
3. API 계약 불변 확인: `GET /api/v1/members/me` 응답 필드가 변경 전과 동일한지 MemberControllerTest 기존 스펙이 그대로 통과해야 한다(수정 대상은 profile JSON 컬럼을 직접 SELECT 하던 검증부뿐).

## 배포 메모

- 마이그레이션 3파일이 한 릴리스에 포함된다: schema → backfill → drop (timestamp 순).
- 롤링 배포 윈도우 동안 구 인스턴스의 member 조회가 실패할 수 있다(research.md R3) — 저트래픽 시간대 배포.
- 실패 시나리오: backfill 실패 → drop 미실행, JSON 원본 보존 → 원인 수정 후 재배포(Flyway 재시도).
