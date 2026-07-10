# Quickstart: member 스키마 재편 (KB-117)

## 검증 절차

### 1. 단위·통합 테스트 (Testcontainers — Docker 필요)

```bash
./gradlew :core:member:test                 # 도메인 단위 (identity 단일화)
./gradlew :infra:persistence:test           # 영속 통합 (MySQL Testcontainers)
./gradlew build                             # 전체 회귀
```

- 영속 테스트 스키마는 Flyway 가 아니라 **Hibernate 가 엔티티에서 생성**한다 — 유니크 제약·ENUM 이 엔티티 `@Table(uniqueConstraints)`/`columnDefinition` 에 선언돼 있어야 재가입·중복 검증이 유효하다.

### 2. Flyway 마이그레이션 검증 (로컬 docker MySQL — 테스트가 못 잡는 부분)

```bash
docker compose up -d meogo-mysql
docker exec -i meogo-mysql mysql -uroot -proot -e "DROP DATABASE IF EXISTS meogo; CREATE DATABASE meogo;"
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun   # 전체 마이그레이션 적용 확인 후 중단
```

이관 데이터 검증(기존 데이터가 있는 DB 라면 DROP 전에 스냅샷을 떠서 비교):

```sql
SHOW CREATE TABLE member;                          -- ENUM·유니크·profile JSON 확인
SELECT id, provider, provider_user_id, email, member_status,
       JSON_PRETTY(profile) FROM member LIMIT 5;
SHOW TABLES LIKE 'member_social_identities';       -- 0건이어야 함
```

### 3. 수용 시나리오 스모크 (영속 통합 테스트가 커버)

| 시나리오 | 테스트 위치 |
|----------|------------|
| 가입→탈퇴→같은 계정 재가입 성공 | `MemberRepositoryAdapterTest` (기존 재가입 테스트를 새 구조로 이관) |
| 탈퇴 행에 원본 식별자·이메일 미잔존 | `MemberRepositoryAdapterTest` (신규) |
| findByIdentity 0/1건 + 중복 saveNew → DUPLICATE_SOCIAL_IDENTITY | `MemberRepositoryAdapterTest` (기존 이관) |
| 정지 회원 서비스 제외 / 관리자(JPA findById) 노출 | `MemberRepositoryAdapterTest` (신규) |
| profile JSON 저장·복원 + onboarding BOOLEAN 복원 | `MemberRepositoryAdapterTest` (기존 프로필 테스트 이관) |

동시 첫 로그인 경합은 범위 제외(R8) — 별도 테스트 없음.
