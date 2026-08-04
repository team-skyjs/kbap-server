# Quickstart: 회원 프로필 JSON 컬럼 평탄화 (KB-297)

## 검증 명령

```bash
# member 도메인 단위 테스트
./gradlew :common:test --tests "com.kbap.common.domain.member.*"

# 프로필 API 통합 테스트 (Testcontainers MySQL, Flyway on + ddl-auto=validate)
./gradlew :api:test --tests "com.kbap.api.member.*"

# 전체 회귀 (SC-002 — 온보딩·프로필 수정·조회 포함 전 테스트)
./gradlew build
```

## 수동 확인 포인트

1. `:api:test` 는 Flyway 전체 마이그레이션으로 스키마를 만들고 `ddl-auto=validate` 가 엔티티↔스키마 정합을 검증한다 — 컬럼 정의 불일치는 부팅 실패로 드러난다.
2. 백필 검증은 개발 중 전용 컨테이너 테스트(`MemberProfileBackfillTest`)로 1회 수행 후 **테스트는 제거**했다(2026-08-05 결정 — 일회성 마이그레이션 검증에 상시 컨테이너 비용을 지불하지 않음). 이후 정합은 Flyway 전체 적용 + `ddl-auto=validate` 가 담당한다.
3. API 계약 불변 확인: `GET /api/v1/members/me` 응답 필드가 변경 전과 동일한지 MemberControllerTest 기존 스펙이 그대로 통과해야 한다(수정 대상은 profile JSON 컬럼을 직접 SELECT 하던 검증부뿐).

## 배포 메모

- 마이그레이션 3파일이 한 릴리스에 포함된다: schema → backfill → drop (timestamp 순).
- 롤링 배포 윈도우 동안 구 인스턴스의 member 조회가 실패할 수 있다(research.md R3) — 저트래픽 시간대 배포.
- 실패 시나리오: backfill 실패 → drop 미실행, JSON 원본 보존 → 원인 수정 후 재배포(Flyway 재시도).
