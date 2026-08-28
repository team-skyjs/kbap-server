# Quickstart: KB-392 검증 절차

## 1. 컨텍스트 수 = 컨테이너 수 (SC-001)

터미널 A: `./gradlew :api:test` (이어서 `:common:test`, `:batch:test`)

터미널 B, 각 모듈 후반부에:

```bash
docker ps --filter ancestor=mysql:8.4 --format '{{.ID}}' | wc -l   # api ≤ 2, common 1, batch 1
```

기준선(변경 전): api 8 · common 7 · batch 3.

## 2. Flyway 실행 횟수 (SC-002)

```bash
./gradlew :api:test 2>&1 | grep -c "Successfully applied\|Schema .* is up to date"   # 2
```

## 3. 본문 무변경 (SC-004)

```bash
git diff develop --stat -- '*Test.kt' | sort -t'|' -k2 -n | tail -5   # 파일당 변경이 헤더·import 수준(≤ ~12줄)인지
git diff develop -- api/src/test | grep '^[-+]' | grep -v '^[-+][-+]' | grep -v '^[-+]import\|^[-+]@\|^[-+]$' | head   # 단언 변경 없어야 함(페이크 파일·login 기본값 제외)
```

## 4. 2회 연속 그린 (SC-003)

```bash
./gradlew clean build && ./gradlew build --rerun-tasks
```

## 5. 규칙 문서 (FR-010)

`CLAUDE.md` 테스트 절에 `@IntegrationTest`/`@BatchIntegrationTest` 규칙, `../kbap-agenthub/INDEX.md` 에 `test-context-consolidation.md`.
