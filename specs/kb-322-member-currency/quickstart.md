# Quickstart: kb-322 회원 통화 설정

## 1. 결정적 검증 (CI — 반드시 통과)

```bash
# 도메인 — 매핑 전수·검증·온보딩 자동 지정·국가 변경 시 불변
./gradlew :common:test --tests "com.kbap.common.domain.member.*"

# HTTP 계약 — 온보딩·프로필 수정·조회
./gradlew :api:test --tests "com.kbap.api.member.*"

# 백필 SQL ↔ enum 정합
./gradlew :api:test --tests "*CurrencySeedSyncTest*"

# 전체 (Flyway 마이그레이션이 Testcontainers MySQL 에 실제로 적용된다)
./gradlew build
```

`./gradlew build` 는 MySQL Testcontainers 를 띄운다(Docker 필요). 마이그레이션은 테스트 프로필에서 **on** 이라, 컬럼 추가·백필 SQL 이 실제 MySQL 에서 검증된다.

**회귀 판정**: `git diff origin/develop...HEAD -- api/src/test/kotlin/com/kbap/api/member/` 에서 기존 블록이 **추가만 있고 기대값 수정이 없어야** 한다.

## 2. 로컬 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

이 기능은 외부 의존이 없다 — LLM·S3·벡터DB 미구성이어도 통화 기능은 동작한다. 다만 api 부팅 자체가 `OPENAI_API_KEY`·`IMAGE_PUBLIC_BASE_URL` 을 요구한다(KB-320 이후).

## 3. 수동 확인 절차

### 온보딩 자동 지정 (US1)

```bash
curl -X POST "http://localhost:8080/api/v1/members/me/onboarding" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"countryCode":"JP","spicinessPreference":"HOT","avoidanceSubstanceCodes":[]}'

curl "http://localhost:8080/api/v1/members/me" -H "Authorization: Bearer <token>"
# → payload.currency == "JPY"
```

통화를 억지로 실어 보내도 무시되는지 확인:
```bash
-d '{"countryCode":"JP","currency":"USD","spicinessPreference":"HOT","avoidanceSubstanceCodes":[]}'
# → payload.currency 는 여전히 "JPY"
```

### 통화만 변경 (US2)

```bash
curl -X PATCH "http://localhost:8080/api/v1/members/me" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"currency":"KRW"}'
# → currency=KRW, countryCode 는 JP 그대로
```

### 국가만 변경 — 통화 불변 (FR-007, 이 기능의 핵심 판정)

```bash
curl -X PATCH "http://localhost:8080/api/v1/members/me" \
  -d '{"countryCode":"US"}'
# → countryCode=US, currency 는 KRW 그대로 (USD 로 바뀌지 않는다)
```

**이게 바뀌면 A안이 깨진 것이다.**

### 잘못된 통화 거절

```bash
curl -X PATCH "http://localhost:8080/api/v1/members/me" -d '{"currency":"krw"}'
# → 400, code=MEMBER-010, 기존 통화 유지 (정확 일치만 허용)
```

## 4. 백필 확인 (US4)

마이그레이션 적용 후 기존 회원을 직접 조회한다.

```sql
-- 국가는 있는데 통화가 비어 있는 회원이 0건이어야 한다
SELECT COUNT(*) FROM member WHERE country_code IS NOT NULL AND currency IS NULL;

-- 국가 없는 회원은 통화도 NULL 인 것이 정상
SELECT COUNT(*) FROM member WHERE country_code IS NULL AND currency IS NOT NULL;  -- 0 이어야 함
```

## 5. 롤백

- **코드만 되돌리기**: 컬럼은 nullable 이라 남아 있어도 구 리비전이 정상 동작한다. 즉시 revert 가능.
- **컬럼까지 제거**: 별도 마이그레이션이 필요하다. 블루/그린 중 신 리비전이 아직 돌고 있으면 컬럼 제거가 그쪽을 깨뜨리므로, **코드 롤백이 전 인스턴스에 반영된 뒤에** 제거한다(contract 단계 분리).

## 6. 함정

- **시드-동기화 테스트가 마이그레이션 파일명을 하드코딩한다.** 파일명·위치를 바꾸면 SQL 을 못 읽어 내용이 빈 문자열이 되고, "파일 없음"이 아니라 **데이터 불일치 실패**로 조용히 깨진다. 테스트 설명에 버전 번호를 박지 말 것.
- **Flyway 버전은 파일 생성 시각 기준 timestamp** (`Vyyyy.MM.dd.HH.mm.ss__...`). 정수 버전 금지, 이미 적용된 마이그레이션 수정 금지.
- `CountryCode` 197줄을 일괄 수정하므로, 국가 라벨을 실수로 건드리지 않았는지 diff 로 확인한다.
