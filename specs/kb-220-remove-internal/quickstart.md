# Quickstart: 검증 절차

성공 기준(SC-001~005)을 그대로 명령으로 옮긴 검증 순서다.

```bash
# SC-001: 영속 타입 잔존 internal 0건
grep -rn "^internal " --include="*.kt" domain/*/src/main | grep -i "Repository\|@Entity"   # 결과 없어야 함
grep -rn "internal constructor" --include="*.kt" domain/*/src/main                          # 결과 없어야 함

# SC-002: 위임 전용 창구 0개
ls domain/food/src/main/kotlin/com/kbap/domain/food/FoodContentBatchService.kt 2>&1          # No such file
ls domain/avoidance/src/main/kotlin/com/kbap/domain/avoidance/AvoidanceCatalogService.kt 2>&1 # No such file

# SC-003: 전체 빌드·테스트 그린 (ArchUnit 포함)
./gradlew build

# SC-004: 문서에서 옛 정책 서술 0건
grep -rn "리포지토리는 .internal.\|internal 로 감춘다\|유일한 공개 창구" \
  CLAUDE.md docs/architecture/ .specify/memory/constitution.md                               # 새 정책 서술만 나와야 함

# SC-005: 배치·홈 동작 동일 — 대상 테스트만 빠르게
./gradlew :domain:food:test :app:batch:test :application:test :app:api:test
```

헌법 개정 확인: `.specify/memory/constitution.md` 머리의 Sync Impact Report 가 `4.0.0 → 5.0.0`, KB-220 근거를 담아야 한다. ADR-0014 가 존재하고 ADR-0012 를 supersede 표기해야 한다.
