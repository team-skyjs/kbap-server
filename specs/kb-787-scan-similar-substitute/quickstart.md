# Quickstart: 유사 음식 대체 로컬 실측

테스트는 작성하지 않으므로(헌법 원칙 I) 검증은 `./gradlew build` 그린 + 아래 수동 실측이다.

## 1. 빌드

```bash
./gradlew build
```

기존 `ScanControllerTest`(2.0 시나리오·1.0 동결)·`OpenApiSnapshotTest`(버전 그룹 가드·스키마 이름 충돌) 가 그대로 통과해야 한다.

## 2. 벡터 **꺼진** 환경 (기본, 로컬) — 대체 생략·null 확인

워크트리에는 `.env` 가 없다 — 메인 체크아웃의 `.env` 를 `set -a; source …; set +a` 로 올리고 `DB_USERNAME=root DB_PASSWORD=root` 로 덮어쓴다(메모리 `worktree-bootrun-env`·`local-mysql-kbap-user-mismatch`).

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon
```

```bash
TOKEN=…           # 로그인 토큰
TICKET=$(curl -s -X POST localhost:8080/api/scans/tickets -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0" | jq -r .payload.ticket)

# 3.0 — 필드 존재 + 대체 없음(false/null)
curl -s -X POST "localhost:8080/api/scans?lang=en&currency=USD" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 3.0" -H "X-Scan-Ticket: $TICKET" \
  -H "Content-Type: application/json" -d '{"imagePath":"<업로드 완료한 메뉴판 경로>"}' \
  | jq '.payload.results[] | {matched, foodId, substituted, originalScannedName}'

# 2.0 — 두 필드 null
# (티켓 재발급 후) -H "X-API-Version: 2.0"  → substituted: null, originalScannedName: null

# 1.0 — 두 필드 없음(기존 요청 계약: items 포함)
```

로그에 임베딩·벡터 호출이 없어야 한다(신규 메뉴가 있어도 seam 빈이 없어 생략).

## 3. 벡터 **켜진** 환경 — 실제 대체 확인

dev 와 같은 S3 Vectors 버킷·임베딩 키가 필요하다(팀 계정 프로필 — 메모리 `aws-account-profiles`).

```bash
EMBEDDING_ENABLED=true VECTOR_ENABLED=true VECTOR_BUCKET=<dev 버킷> VECTOR_INDEX=foods \
OPENAI_API_KEY=… SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon
```

1. DB 에 없는 표기이면서 완성 음식과 가까운 이름(예: 오타 "김치찌게" — "김치 찌개" 는 matchKey 로 이미 매칭되므로 안 된다)이 적힌 메뉴판을 3.0 으로 스캔.
2. 기대: 해당 항목 `matched=true`, `foodId`=완성 음식 id, `substituted=true`, `originalScannedName="김치찌게"`, `koreanName="김치찌개"`.
3. `SELECT food_id FROM scan_history ORDER BY id DESC LIMIT 3;` → 대체 음식 id. `SELECT id, content_status FROM food WHERE korean_name='김치찌게';` → FAILED 행이 그대로 존재.
4. `GET /api/foods/scanned`(최근 스캔)에 대체 음식이 보인다.

## 4. 임계값

코드 상수 0.6(`SimilarFoodService.SIMILARITY_THRESHOLD`). 환경변수로 바꿀 수 없다 — 경계값 확인이 필요하면 상수를 임시로 바꿔 재기동한다.

## 5. 실패 격리 (US3-4)

`VECTOR_BUCKET` 을 존재하지 않는 값으로 바꿔 기동 → 스캔은 200, 신규 항목은 준비중, 로그에 `유사 음식 검색 실패` warn 1건.

## 6. 미지원 버전

`-H "X-API-Version: 2.5"` → 400 `COMMON-002`. `3.0` 이 400 이면 `WebConfig.addSupportedVersions` 등록 누락이다.
