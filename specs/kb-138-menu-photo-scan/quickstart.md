# Quickstart: 메뉴판 사진 스캔 (KB-138)

## 전제

- 브랜치 `kb-138-menu-photo-scan`, 플랜 `specs/kb-138-menu-photo-scan/plan.md`.
- 서명 URL 발급·실 S3 업로드는 KB-145 범위 — 이 기능은 "업로드가 끝난 뒤"부터.
- local·테스트에서 실 S3/OpenAI 는 붙지 않는다(`@ConditionalOnProperty` 미구성 → 빈 미생성, 테스트는 페이크).

## 로컬 실행

```bash
./gradlew :app:api:bootRun   # SPRING_PROFILES_ACTIVE=local
```

vision·스토리지 활성화(dev/prod 또는 로컬 수동 검증 시) — `.env` 또는 환경변수:

```properties
kbap.llm.vision.enabled=true
kbap.llm.vision.api-key=sk-...
kbap.llm.vision.model=gpt-4o-mini
kbap.llm.vision.image-base-url=https://cdn.example.com   # CDN 도메인 — path 와 조합
kbap.storage.enabled=true
kbap.storage.bucket=kbap-images
kbap.storage.region=ap-northeast-2
```

## 수동 검증 시나리오

```bash
# 1) (KB-145) presigned URL 발급 → S3 PUT 업로드 → path 확보

# 2) 업로드 완료 신고
curl -X POST localhost:8080/api/v1/images/complete \
  -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
  -d '{"path":"scan/1/xxx.jpg","contentType":"image/jpeg","size":1048576}'

# 3) 사진 스캔
curl -X POST localhost:8080/api/v1/scans \
  -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
  -d '{"imagePath":"scan/1/xxx.jpg"}'
# → results[].{idx, matched, foodId, riskLevel, name, koreanName, price}
```

## 테스트

```bash
./gradlew :domain:image:test          # ImageUploadServiceTest (페이크 StorageObjectStore)
./gradlew :domain:scan:test           # ScanServiceTest (페이크 vision·image 창구)
./gradlew :infra:llm:test             # MenuBoardResultParser 단위(JSON·가격 축약·오류)
./gradlew :app:api:test               # ImageController·ScanController MockMvc 통합
./gradlew test                        # 전체 (ArchUnit 포함 — 신규 모듈 경계 검증)
```

전부 Kotest BehaviorSpec(한국어 given/when/then), Red → Green → Refactor.
