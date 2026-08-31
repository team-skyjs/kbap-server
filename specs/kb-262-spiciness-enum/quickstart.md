# Quickstart: 맵기 선호 ENUM 전환 검증

## 빌드·테스트

```bash
./gradlew :common:test --tests "*MemberProfile*" --tests "*SpicinessPreference*"
./gradlew :api:test --tests "*MemberControllerTest*" --tests "*AdminControllerTest*"
./gradlew build          # 전체 회귀(FR-007) — 통합 테스트가 Flyway 이관까지 검증
```

통합 테스트는 Testcontainers MySQL 에 운영과 동일한 Flyway 마이그레이션을 적용하므로, 신규 이관 SQL 도 테스트 부팅 경로에서 함께 검증된다.

## 수동 확인 (local 프로필)

```bash
./gradlew :api:bootRun    # SPRING_PROFILES_ACTIVE=local
```

1. 온보딩: `POST /api/v1/members/onboarding` 에 `"spicinessPreference": "HOT"` → 200, `"5"` 나 `7` → 400 `MEMBER-009`.
2. 조회: `GET /api/v1/members/me` → `payload.spicinessPreference` 가 `"HOT"`.
3. 수정: `PATCH /api/v1/members/me` 에 `"spicinessPreference": "MILD"` → 조회 시 `"MILD"`, 필드 생략 → 기존 값 유지.
4. 이관: 기존 정수 데이터가 있는 DB 라면 마이그레이션 후 `SELECT profile->>'$.spicinessPreference' FROM member` 가 전부 6단계 문자열인지 확인.
